package dev.overhaul.minecraft.blaze;

/** One thing the performance overlay can show, in the order it shows them. */
public enum BlazeOverlayItem {
    FPS("fps", "Frame rate", "Frames per second, averaged over each refresh"),
    FRAME_TIME("frameTime", "Frame time", "Milliseconds per frame, averaged over each refresh"),
    LOWS("lows", "1% and 0.1% lows", "The frame rate of the slowest 1% and 0.1% of the last thousand frames. Shows stutter an average hides"),
    GRAPH("graph", "Frame time graph", "The time of each recent frame as a bar; the line marks 60 fps"),
    GPU_TIME("gpuTime", "GPU time", "How long the GPU spent on the last frame, from two timestamps per frame. Read a few frames late, so nothing waits"),
    CPU("cpu", "CPU load", "How busy the game's process keeps the processor, across all cores"),
    MEMORY("memory", "Java memory", "Heap in use and the most the game may take"),
    VRAM("vram", "Video memory", "Video memory the game holds and the budget the driver gives it"),
    RENDER("render", "Resolution", "The size the world draws at, and the upscaler that fills the screen from it"),
    DEVICE("device", "Graphics card", "The GPU the renderer runs on"),
    CHUNKS("chunks", "Chunk sections", "Chunk sections drawn this frame"),
    ENTITIES("entities", "Entities", "Entities drawn, and the total in the level"),
    POSITION("position", "Position", "Block coordinates and the direction you face");

    public final String key, label, description;

    BlazeOverlayItem(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }
}
