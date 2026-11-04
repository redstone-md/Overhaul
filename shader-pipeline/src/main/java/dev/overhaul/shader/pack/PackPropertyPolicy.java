package dev.overhaul.shader.pack;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Properties a pack can set that this pipeline does not honor but that leave its shaders valid:
 * the pack runs with default behavior for them. Keys that change what shaders read or compute
 * (scale.*, bufferObject.*, image.*, indirect.*, iris.features.required) stay out of this list and
 * remain blocking, as does every unknown key. Custom uniform definitions are evaluated, not ignored.
 */
final class PackPropertyPolicy {
    private static final Set<String> IGNORED = Set.of("clouds","dhClouds","oldHandLight","dynamicHandLight","oldLighting",
            "shadowTerrain","shadowTranslucent","shadowEntities","shadowPlayer","shadowBlockEntities","shadowLightBlockEntities",
            "underwaterOverlay","sun","moon","stars","sky","vignette","rain.depth","beacon.beam.depth","separateAo",
            "voxelizeLightBlocks","frustum.culling","occlusion.culling","shadow.culling","shadow.enabled","dhShadow.enabled",
            "particles.before.deferred","separateEntityDraws","prepareBeforeShadow","supportsColorCorrection",
            "allowConcurrentCompute","breaksAnisotropy","iris.features.optional","weather","fallbackTex");
    private static final Pattern IGNORED_FAMILY = Pattern.compile("backFace\\..*|particles\\.ordering.*|version\\..*");

    private PackPropertyPolicy() {}

    static boolean ignored(String key) { return IGNORED.contains(key) || IGNORED_FAMILY.matcher(key).matches(); }
}
