package dev.overhaul.minecraft.blaze;

/** One thing the performance overlay can show, in the order it shows them. */
public enum BlazeOverlayItem {
    FPS("fps", "Frame rate", "Frames per second, averaged over each refresh"),
    FRAME_TIME("frameTime", "Frame time", "Milliseconds per frame, averaged over each refresh"),
    LOWS("lows", "1% and 0.1% lows", "The frame rate of the slowest 1% and 0.1% of the last thousand frames. Shows stutter an average hides"),
    GRAPH("graph", "Frame time graph", "The time of each recent frame as a bar; the line marks 60 fps"),
    GPU_TIME("gpuTime", "GPU time", "How long the GPU spent on the last frame, from two timestamps per frame. Read a few frames late, so nothing waits"),
    GPU_LOAD("gpuLoad", "GPU load", "How busy the graphics card is, as its driver reports it"),
    GPU_TEMPERATURE("gpuTemperature", "GPU temperature", "The core temperature of the graphics card. Needs an NVIDIA card"),
    GPU_CLOCK("gpuClock", "GPU clock", "The clock of the graphics core right now. Needs an NVIDIA card"),
    GPU_POWER("gpuPower", "GPU power", "Watts the graphics card draws. Needs an NVIDIA card"),
    GPU_FAN("gpuFan", "GPU fan", "Fan speed as a share of its top speed. Needs an NVIDIA card"),
    CPU("cpu", "CPU load", "How busy the game's process keeps the processor, across all cores"),
    CPU_CLOCK("cpuClock", "CPU clock", "The clock of the processor right now, averaged over its cores"),
    THERMAL_ZONE("thermalZone", "Board thermal zone", "The hottest thermal zone the board reports. Not the processor core sensor: Windows gives that to drivers only, and many boards report a fixed figure"),
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
