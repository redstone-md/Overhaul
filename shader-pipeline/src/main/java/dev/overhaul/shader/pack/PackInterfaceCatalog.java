package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.LegacyShaderContract;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.pack.PackDiagnostic.Category;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a program may declare and still run. Declaration and SPIR-V compilation accept any name, so
 * a miss here otherwise surfaces only as "Unbound ..." when a frame is first built. BOUND reflects
 * what the Minecraft adapter's frame uniforms fill today and must change with it; this module cannot
 * see that code. Types of UNBOUND names come from Iris and OptiFine usage and are not exercised.
 */
public final class PackInterfaceCatalog {
    public enum Binding { BOUND, UNBOUND }
    public record UniformInfo(String type, Binding binding) {}

    private static final Map<String,UniformInfo> UNIFORMS = uniforms();
    private static final Set<String> SCENE_SAMPLERS = Set.of("texture","tex","gtexture","lightmap");
    /** The adapter rewrites this one uniform to a varying on entity pipelines. */
    private static final String ENTITY_COLOR = "entityColor";

    private PackInterfaceCatalog() {}

    private static Map<String,UniformInfo> uniforms() {
        Map<String,UniformInfo> table = new HashMap<>();
        add(table,Binding.BOUND,"float","viewWidth","viewHeight","aspectRatio","frameTime","frameTimeCounter","near","far",
                "rainStrength","thunderStrength","eyeAltitude","screenBrightness","pi","sunAngle","shadowAngle");
        add(table,Binding.BOUND,"int","frameCounter","worldTime","worldDay","moonPhase","isEyeInWater");
        add(table,Binding.BOUND,"ivec2","eyeBrightness");
        add(table,Binding.BOUND,"vec3","cameraPosition","previousCameraPosition","fogColor","skyColor","sunPosition","moonPosition",
                "shadowLightPosition","upPosition");
        add(table,Binding.BOUND,"mat4","gbufferProjection","gbufferProjectionInverse","gbufferModelView","gbufferModelViewInverse",
                "gbufferPreviousProjection","gbufferPreviousModelView","shadowProjection","shadowProjectionInverse","shadowModelView","shadowModelViewInverse");
        add(table,Binding.UNBOUND,"int","fogMode","fogShape","heldItemId","heldBlockLightValue","heldItemId2","heldBlockLightValue2",
                "entityId","blockEntityId","hideGUI","renderStage","bossBattle","terrainIconSize");
        add(table,Binding.UNBOUND,"float","fogStart","fogEnd","fogDensity","wetness","nightVision","blindness","darknessFactor",
                "darknessLightFactor","playerMood","centerDepthSmooth","alphaTestRef");
        add(table,Binding.UNBOUND,"ivec2","eyeBrightnessSmooth","atlasSize","terrainTextureSize");
        add(table,Binding.UNBOUND,"ivec4","blendFunc");
        add(table,Binding.UNBOUND,"vec4",ENTITY_COLOR,"colorModulator","spriteBounds");
        add(table,Binding.UNBOUND,"vec3","chunkOffset");
        add(table,Binding.UNBOUND,"mat4","modelViewMatrix","modelViewMatrixInverse","projectionMatrix","projectionMatrixInverse","textureMatrix");
        add(table,Binding.UNBOUND,"mat3","normalMatrix");
        return Map.copyOf(table);
    }
    private static void add(Map<String,UniformInfo> table, Binding binding, String type, String... names) {
        for (String name : names) table.put(name,new UniformInfo(type,binding));
    }

    public static Optional<UniformInfo> uniform(String name) { return Optional.ofNullable(UNIFORMS.get(name)); }

    /**
     * Judges the active interface of one compiled program. Generated overhaul* and irisCompat* uniforms
     * belong to the translator and adapter contracts and are not judged. {@code customTextures} names
     * samplers the pack supplies through properties.
     */
    public static List<PackDiagnostic> check(String program, ProgramBindings bindings, boolean scene, Set<String> customTextures) {
        List<PackDiagnostic> diagnostics = new ArrayList<>();
        for (var entry : new TreeMap<>(bindings.uniforms()).entrySet()) checkUniform(program,entry.getKey(),entry.getValue().type(),scene,diagnostics);
        for (String sampler : new TreeMap<>(bindings.samplers()).keySet()) {
            if (customTextures.contains(sampler) || sampler.equals("noisetex")) continue;
            if (!bound(sampler,scene)) diagnostics.add(PackDiagnostic.blocking(Category.SAMPLER,program+"."+sampler,"No image is bound to this sampler"));
            else if (bindings.comparisonSampler(sampler) && PackShadowSettings.depthIndex(sampler) < 0) {
                diagnostics.add(PackDiagnostic.blocking(Category.SAMPLER,program+"."+sampler,"Depth comparison requires a shadow depth texture"));
            }
        }
        if (scene) for (String attribute : new TreeMap<>(bindings.attributes()).keySet()) {
            if (!LegacyShaderContract.ATTRIBUTE_TYPES.containsKey(attribute)) {
                diagnostics.add(PackDiagnostic.blocking(Category.ATTRIBUTE,program+"."+attribute,"Scene geometry does not supply this vertex attribute"));
            }
        }
        return List.copyOf(diagnostics);
    }

    private static void checkUniform(String program, String name, String type, boolean scene, List<PackDiagnostic> diagnostics) {
        if (generated(name)) return;
        String subject = program+"."+name;
        UniformInfo known = UNIFORMS.get(name);
        if (known == null) diagnostics.add(PackDiagnostic.blocking(Category.UNIFORM,subject,"Unknown uniform; custom uniform.* and variable.* definitions are not evaluated"));
        else if (!known.type().equals(type)) diagnostics.add(PackDiagnostic.blocking(Category.UNIFORM,subject,"Declared "+type+" but this uniform is "+known.type()));
        else if (name.equals(ENTITY_COLOR) && scene) diagnostics.add(PackDiagnostic.info(Category.UNIFORM,subject,"Bound only on entity pipelines, where the adapter lowers it"));
        else if (known.binding() == Binding.UNBOUND) diagnostics.add(PackDiagnostic.blocking(Category.UNIFORM,subject,"Known uniform with no value source yet"));
    }

    private static boolean bound(String sampler, boolean scene) {
        return PackShadowSettings.depthIndex(sampler) >= 0 || PackShadowSettings.colorIndex(sampler) >= 0
                || PackRenderTargets.colorIndex(sampler) >= 0 || PackRenderTargets.depthIndex(sampler) >= 0
                || (scene && (SCENE_SAMPLERS.contains(sampler) || generated(sampler)));
    }

    private static boolean generated(String name) { return name.startsWith("overhaul") || name.startsWith("irisCompat"); }
}
