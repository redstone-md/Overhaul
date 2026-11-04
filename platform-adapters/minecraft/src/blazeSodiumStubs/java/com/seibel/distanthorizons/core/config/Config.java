package com.seibel.distanthorizons.core.config;

import com.seibel.distanthorizons.core.config.types.ConfigEntry;

public class Config {
    public static class Client {
        public static class Advanced {
            public static class Graphics {
                public static class Quality {
                    public static ConfigEntry<Integer> lodChunkRenderDistanceRadius;
                    public static ConfigEntry<Boolean> ditherDhFade;
                }
                public static class NoiseTexture {
                    public static ConfigEntry<Boolean> enableNoiseTexture;
                    public static ConfigEntry<Integer> noiseSteps, noiseDropoff;
                    public static ConfigEntry<Float> noiseIntensity;
                }
                public static class Experimental {
                    public static ConfigEntry<Integer> earthCurveRatio;
                }
            }
            public static class Debugging {
                public static ConfigEntry<Boolean> enableWhiteWorld, lodOnlyMode;
            }
        }
    }
}
