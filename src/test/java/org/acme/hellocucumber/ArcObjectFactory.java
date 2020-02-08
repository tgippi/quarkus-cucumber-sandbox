package org.acme.hellocucumber;

import io.cucumber.core.backend.ObjectFactory;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

public class ArcObjectFactory implements ObjectFactory {
    private ArcContainer injector;

    public ArcObjectFactory() {
        // Create an injector with our service module
        this.injector = Arc.container();
    }

    @Override
    public boolean addClass(Class<?> clazz) {
        return true;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public <T> T getInstance(Class<T> clazz) {
        return Arc.container().instance(clazz).get();
    }
}