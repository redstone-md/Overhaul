package com.seibel.distanthorizons.coreapi.DependencyInjection;

public class ApiEventInjector {
    public static final ApiEventInjector INSTANCE = null;

    public native <T> boolean fireAllEvents(Class<?> event, T param);

    public native java.util.ArrayList<?> getAll(Class<?> type);
}