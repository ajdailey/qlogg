package io.github.ajdailey.qlogg.app.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that runs on a worker, never on the UI thread, so it may make blocking file and
 * document calls. Every caller is itself a worker method or the body of a task submitted to {@code
 * Workers}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface WorkerThread {}
