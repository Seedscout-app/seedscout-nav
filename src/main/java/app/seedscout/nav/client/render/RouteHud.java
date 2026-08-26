package app.seedscout.nav.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Deliverable 5's "HUD readout of distance to the destination". A single line of text in the
 * top-left corner, drawn only while a route is actually shown (see
 * {@link RouteRenderer#distanceToDestination()}).
 *
 * <p><b>Deliberately does not display the route label.</b> The task brief's deliverable 5 is
 * a distance readout, not a label readout, so this HUD element never reads
 * {@code RouteFrame.label()} or {@link app.seedscout.nav.protocol.SafeLabel#literalText()} at
 * all. {@code SafeLabel}'s stripping and truncation exist as defense in depth against the
 * label reaching a render surface, in case a future deliverable adds one; that surface does
 * not exist yet, here or anywhere else in this package. If a label is ever displayed, it must
 * go through {@code SafeLabel.literalText()} exactly as this class does for the distance
 * string today (a plain {@code String} handed to {@code graphics.text(...)}, never a text
 * component and never a translation key).
 *
 * <p>Registered once, in {@link app.seedscout.nav.client.SeedscoutNavClientHooks#init()},
 * via Fabric's layer-based {@code HudElementRegistry} rather than the old single
 * {@code HudRenderCallback}: Minecraft 26.x's retained-mode GUI rewrite replaced
 * {@code GuiGraphics.render(...)} callbacks with {@code extractRenderState(...)} everywhere,
 * HUD elements included. See the integrator report for the full list of what moved.
 */
public final class RouteHud implements HudElement {

    public static final RouteHud INSTANCE = new RouteHud();

    private static final int MARGIN_X = 6;
    private static final int MARGIN_Y = 40; // below the vanilla status/effect icons row
    private static final int TEXT_COLOR = 0xFFFFFF;

    private RouteHud() {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Double distance = RouteRenderer.INSTANCE.distanceToDestination();
        if (distance == null) {
            return;
        }
        String text = "Seedscout route: " + Math.round(distance) + "m";
        graphics.text(Minecraft.getInstance().font, text, MARGIN_X, MARGIN_Y, TEXT_COLOR);
    }
}
