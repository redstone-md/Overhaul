package dev.overhaul.minecraft.blaze;

/** A level renderer whose layer lists may already hold only the sections with geometry in that layer. */
public interface BlazeLayerLists {
    /** Whether the lists handed out this frame come from such a scan, which spares asking each section again. */
    boolean overhaul$layerListed();
    /** The flat draw data of a layer, null when draws must walk its sections instead. */
    BlazeVisibleSections.Layer overhaul$layerTable(net.minecraft.client.renderer.RenderType layer);
}
