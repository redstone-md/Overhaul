package dev.overhaul.core.model;

/** Opaque and cutout write depth; translucent draws last, sorted back-to-front without depth writes. */
public enum RenderLayer { OPAQUE, CUTOUT, TRANSLUCENT }
