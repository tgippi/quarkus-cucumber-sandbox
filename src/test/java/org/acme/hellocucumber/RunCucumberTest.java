package org.acme.hellocucumber;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import io.quarkus.bootstrap.BootstrapClassLoaderFactory;
import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.DefineClassVisibleURLClassLoader;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.util.PropertyUtils;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.ClassOutput;
import io.quarkus.deployment.QuarkusClassWriter;
import io.quarkus.deployment.builditem.TestAnnotationBuildItem;
import io.quarkus.deployment.builditem.TestClassPredicateBuildItem;
import io.quarkus.deployment.util.IoUtil;
import io.quarkus.runner.RuntimeRunner;
import io.quarkus.runner.TransformerTarget;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.http.TestHTTPResourceManager;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.quarkus.test.common.PathTestHelper.getAppClassLocation;
import static io.quarkus.test.common.PathTestHelper.getTestClassesLocation;

@RunWith(Cucumber.class)
@CucumberOptions(plugin = {"pretty"}, features = "src/test/resources/hellocucumber", objectFactory = ArcObjectFactory.class)
public class RunCucumberTest {

    private static URLClassLoader appCl;
    private static ClassLoader originalCl;
    private static final String CREATED_FILES = "CREATED_FILES.txt";

    @BeforeClass
    public static void before() {
        doJavaStart();
    }

    @AfterClass
    public static void after() {

    }

    private static void doJavaStart() {
        final LinkedBlockingDeque<Runnable> shutdownTasks = new LinkedBlockingDeque<>();

        Path appClassLocation = getAppClassLocation(RunCucumberTest.class);

        appCl = createQuarkusBuildClassLoader(appClassLocation);
        originalCl = setCCL(appCl);

        final ClassLoader testClassLoader = RunCucumberTest.class.getClassLoader();
        final Path testWiringClassesDir;
        final RuntimeRunner.Builder runnerBuilder = RuntimeRunner.builder();

        final Path testClassLocation = getTestClassesLocation(RunCucumberTest.class);
        if (Files.isDirectory(testClassLocation)) {
            testWiringClassesDir = testClassLocation;
        } else {
            if (!appClassLocation.equals(testClassLocation)) {
                runnerBuilder.addAdditionalArchive(testClassLocation);
            }
            testWiringClassesDir = Paths.get("").normalize().toAbsolutePath().resolve("target").resolve("test-classes");
            if (Files.exists(testWiringClassesDir)) {
                IoUtils.recursiveDelete(testWiringClassesDir);
            }
            try {
                Files.createDirectories(testWiringClassesDir);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to create a directory for wiring test classes at " + testWiringClassesDir, e);
            }
        }

        Path createdFilesPath = testWiringClassesDir.resolve(CREATED_FILES);
        if (Files.exists(createdFilesPath)) {
            cleanupOldRun(createdFilesPath);
        }
        try (OutputStream created = Files.newOutputStream(createdFilesPath)) {

            RuntimeRunner runtimeRunner = runnerBuilder
                    .setLaunchMode(LaunchMode.TEST)
                    .setClassLoader(appCl)
                    .setTarget(appClassLocation)
                    .addAdditionalArchive(testWiringClassesDir)
                    .setClassOutput(new ClassOutput() {
                        @Override
                        public void writeClass(boolean applicationClass, String className, byte[] data) throws IOException {
                            Path location = testWiringClassesDir.resolve(className.replace('.', '/') + ".class");
                            Files.createDirectories(location.getParent());
                            Files.write(location, data);
                            handleCreatedFile(location, created, testWiringClassesDir, shutdownTasks);
                        }

                        @Override
                        public void writeResource(String name, byte[] data) throws IOException {
                            Path location = testWiringClassesDir.resolve(name);
                            Files.createDirectories(location.getParent());
                            Files.write(location, data);
                            handleCreatedFile(location, created, testWiringClassesDir, shutdownTasks);
                        }
                    })
                    .setTransformerTarget(new TransformerTarget() {
                        @Override
                        public void setTransformers(
                                Map<String, List<BiFunction<String, ClassVisitor, ClassVisitor>>> functions) {
                            ClassLoader main = Thread.currentThread().getContextClassLoader();

                            //we need to use a temp class loader, or the old resource location will be cached
                            ClassLoader temp = new ClassLoader() {
                                @Override
                                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                                    // First, check if the class has already been loaded
                                    Class<?> c = findLoadedClass(name);
                                    if (c == null) {
                                        c = findClass(name);
                                    }
                                    if (resolve) {
                                        resolveClass(c);
                                    }
                                    return c;
                                }

                                @Override
                                public URL getResource(String name) {
                                    return main.getResource(name);
                                }

                                @Override
                                public Enumeration<URL> getResources(String name) throws IOException {
                                    return main.getResources(name);
                                }
                            };
                            for (Map.Entry<String, List<BiFunction<String, ClassVisitor, ClassVisitor>>> e : functions
                                    .entrySet()) {
                                String resourceName = e.getKey().replace('.', '/') + ".class";
                                try (InputStream stream = temp.getResourceAsStream(resourceName)) {
                                    if (stream == null) {
                                        System.err.println("Failed to transform " + e.getKey());
                                        continue;
                                    }
                                    byte[] data = IoUtil.readBytes(stream);

                                    ClassReader cr = new ClassReader(data);
                                    ClassWriter cw = new QuarkusClassWriter(cr,
                                            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {

                                        @Override
                                        protected ClassLoader getClassLoader() {
                                            // this has been previously set to a safe for transformations CL
                                            return main;
                                        }
                                    };
                                    ClassLoader old = Thread.currentThread().getContextClassLoader();
                                    Thread.currentThread().setContextClassLoader(temp);
                                    try {
                                        ClassVisitor visitor = cw;
                                        for (BiFunction<String, ClassVisitor, ClassVisitor> i : e.getValue()) {
                                            visitor = i.apply(e.getKey(), visitor);
                                        }
                                        cr.accept(visitor, 0);
                                    } finally {
                                        Thread.currentThread().setContextClassLoader(old);
                                    }

                                    Path location = testWiringClassesDir.resolve(resourceName);
                                    Files.createDirectories(location.getParent());
                                    Files.write(location, cw.toByteArray());
                                    handleCreatedFile(location, created, testWiringClassesDir, shutdownTasks);
                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }
                    })
                    .addChainCustomizer(new Consumer<BuildChainBuilder>() {
                        @Override
                        public void accept(BuildChainBuilder buildChainBuilder) {
                            buildChainBuilder.addBuildStep(new BuildStep() {
                                @Override
                                public void execute(BuildContext context) {
                                    context.produce(new TestClassPredicateBuildItem(new Predicate<String>() {
                                        @Override
                                        public boolean test(String className) {
                                            return PathTestHelper.isTestClass(className, testClassLoader);
                                        }
                                    }));
                                }
                            }).produces(TestClassPredicateBuildItem.class)
                                    .build();
                        }
                    })
                    .addChainCustomizer(new Consumer<BuildChainBuilder>() {
                        @Override
                        public void accept(BuildChainBuilder buildChainBuilder) {
                            buildChainBuilder.addBuildStep(new BuildStep() {
                                @Override
                                public void execute(BuildContext context) {
                                    context.produce(new TestAnnotationBuildItem(QuarkusTest.class.getName()));
                                }
                            }).produces(TestAnnotationBuildItem.class)
                                    .build();
                        }
                    })
                    .build();
            runtimeRunner.run();
            System.setProperty("test.url", TestHTTPResourceManager.getUri());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static URLClassLoader createQuarkusBuildClassLoader(Path appClassLocation) {
        // The deployment classpath could be passed in as a system property.
        // This is how integration with the Gradle plugin is achieved.
        final String deploymentCp = PropertyUtils.getProperty(BootstrapClassLoaderFactory.PROP_DEPLOYMENT_CP);
        if (deploymentCp != null && !deploymentCp.isEmpty()) {
            final List<URL> list = new ArrayList<>();
            for (String entry : deploymentCp.split("\\s")) {
                try {
                    list.add(new URL(entry));
                } catch (MalformedURLException e) {
                    throw new IllegalStateException("Failed to parse a deployment classpath entry " + entry, e);
                }
            }
            return new DefineClassVisibleURLClassLoader(list.toArray(new URL[list.size()]), RunCucumberTest.class.getClassLoader());
        }
        try {
            return BootstrapClassLoaderFactory.newInstance()
                    .setAppClasses(appClassLocation)
                    .setParent(RunCucumberTest.class.getClassLoader())
                    .setOffline(PropertyUtils.getBooleanOrNull(BootstrapClassLoaderFactory.PROP_OFFLINE))
                    .setLocalProjectsDiscovery(
                            PropertyUtils.getBoolean(BootstrapClassLoaderFactory.PROP_WS_DISCOVERY, true))
                    .setEnableClasspathCache(PropertyUtils.getBoolean(BootstrapClassLoaderFactory.PROP_CP_CACHE, true))
                    .newDeploymentClassLoader();
        } catch (BootstrapException e) {
            throw new IllegalStateException("Failed to create the boostrap class loader", e);
        }
    }

    private static void handleCreatedFile(Path location, OutputStream created, Path testWiringClassesDir,
                                   LinkedBlockingDeque<Runnable> shutdownTasks) throws IOException {
        created.write((testWiringClassesDir.relativize(location).toString() + "\n").getBytes(StandardCharsets.UTF_8));
        created.flush();
    }

    private static void cleanupOldRun(Path createdFilesPath) {
        try (BufferedReader reader = Files.newBufferedReader(createdFilesPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Files.deleteIfExists(createdFilesPath.getParent().resolve(line));
            }
            Files.deleteIfExists(createdFilesPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static ClassLoader setCCL(ClassLoader cl) {
        final Thread thread = Thread.currentThread();
        final ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(cl);
        return original;
    }

}