package app.seedscout.nav.client.gui;

import app.seedscout.nav.client.NavSession;
import app.seedscout.nav.client.qr.QrCode;
import app.seedscout.nav.link.LinkServer;
import app.seedscout.nav.protocol.NavProtocol;
import app.seedscout.nav.protocol.UnlinkFrame;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Deliverable 2 and 4: the in-game pairing screen. Renders the {@code seedscout://pair} QR
 * code (section 3 of {@code shared/nav_protocol.md}), a visible countdown to the 120 second
 * token expiry, an explicit warning that the code is a bearer secret, and then the consent
 * step once a peer's handshake is accepted.
 *
 * <p>Driven entirely by polling {@link LinkServer#state()} once per tick ({@link #tick()},
 * still called every client tick by {@code Screen}'s own lifecycle in the 26.x rewrite,
 * unlike {@code render(...)} which no longer exists at all): {@link LinkServer} has no
 * observer/callback mechanism, so this is the natural way for a UI to track it without
 * reaching into its internals.
 *
 * <p>The accept prompt names the connecting device's numeric IP address (task brief,
 * deliverable 4; FINDING M3b), via {@link LinkServer#pendingPeerAddress()}: that is the only
 * way a player can tell their own phone from a shoulder-surfer or stream viewer who captured
 * the QR, since without it the prompt looks identical in both cases. Read once on first
 * observing {@link LinkServer.State#AWAITING_CONFIRMATION} and cached in
 * {@link #pendingPeerAddress}, per that accessor's own documented contract (present only
 * while in that state).
 */
public final class PairScreen extends Screen {

    private final LinkServer server;
    private final long deadlineMillis;

    private LinkServer.State lastPhase;
    private QrCode qrCode;
    private String pairingUri;
    private String pendingPeerAddress;

    public PairScreen(LinkServer server) {
        super(Component.literal("Seedscout Nav Pairing"));
        this.server = server;
        this.deadlineMillis = System.currentTimeMillis() + NavProtocol.TOKEN_TTL.toMillis();
    }

    @Override
    protected void init() {
        rebuildForPhase(server.state());
    }

    @Override
    public void tick() {
        LinkServer.State phase = server.state();
        if (phase != lastPhase) {
            rebuildForPhase(phase);
        }
        if (phase == LinkServer.State.LINKED) {
            // Confirmed: the session is live, nothing more for this screen to do.
            minecraft.setScreenAndShow(null);
        }
    }

    private void rebuildForPhase(LinkServer.State phase) {
        lastPhase = phase;
        clearWidgets();
        switch (phase) {
            case LISTENING -> {
                if (pairingUri == null) {
                    pairingUri = server.pairingUri();
                    qrCode = QrCode.encodeAscii(pairingUri);
                }
                addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                        .bounds(width / 2 - 50, height - 30, 100, 20)
                        .build());
            }
            case AWAITING_CONFIRMATION -> {
                // Read once on first observing this state and cached, per
                // LinkServer#pendingPeerAddress's documented contract: it is only present
                // while state() == AWAITING_CONFIRMATION, so this is the one moment to grab
                // it for a phase that may still be showing this same prompt several ticks
                // from now while awaiting the player's click.
                pendingPeerAddress = server.pendingPeerAddress().orElse(null);
                addRenderableWidget(Button.builder(Component.literal("Accept"), b -> server.confirm())
                        .bounds(width / 2 - 105, height / 2 + 40, 100, 20)
                        .build());
                addRenderableWidget(Button.builder(Component.literal("Decline"), b -> declineAndClose())
                        .bounds(width / 2 + 5, height / 2 + 40, 100, 20)
                        .build());
            }
            case CLOSED -> addRenderableWidget(
                    Button.builder(Component.literal("Close"), b -> minecraft.setScreenAndShow(null))
                            .bounds(width / 2 - 50, height / 2 + 40, 100, 20)
                            .build());
            case LINKED -> {
                // Handled in tick(): the screen closes itself the instant this is observed.
            }
        }
    }

    private void declineAndClose() {
        server.reject();
        minecraft.setScreenAndShow(null);
    }

    @Override
    public void onClose() {
        // The player closed this screen some other way (Escape, world change, etc). A
        // LISTENING or AWAITING_CONFIRMATION window left open with nobody watching it is
        // exactly the "leave the app to time out cleanly" case the task brief asks for:
        // end the session rather than leave a stale listener bound. A CLOSED/LINKED server
        // is a no-op here (unlink() is idempotent).
        NavSession.INSTANCE.endSession(UnlinkFrame.PLAYER_ENDED);
        super.onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractMenuBackground(graphics);
        drawContentForPhase(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawContentForPhase(GuiGraphicsExtractor graphics) {
        int centerX = width / 2;
        graphics.centeredText(font, title, centerX, 16, 0xFFFFFF);

        switch (server.state()) {
            case LISTENING -> drawListening(graphics, centerX);
            case AWAITING_CONFIRMATION -> drawAwaitingConfirmation(graphics, centerX);
            case CLOSED -> graphics.centeredText(
                    font, "Pairing window closed or expired.", centerX, height / 2, 0xFF5555);
            case LINKED -> graphics.centeredText(font, "Linked.", centerX, height / 2, 0x55FF55);
        }
    }

    private void drawListening(GuiGraphicsExtractor graphics, int centerX) {
        long remainingMillis = deadlineMillis - System.currentTimeMillis();
        long remainingSeconds = Math.max(0, remainingMillis / 1000);
        graphics.centeredText(
                font, "Scan with the Seedscout app to pair", centerX, 32, 0xCCCCCC);
        graphics.centeredText(
                font, "Expires in " + remainingSeconds + "s", centerX, 44, 0xCCCCCC);

        if (qrCode != null) {
            drawQrCode(graphics, centerX);
        }

        graphics.centeredText(
                font,
                "Anyone who can see this code can pair with your game.",
                centerX,
                height - 54,
                0xFF9955);
        graphics.centeredText(
                font,
                "Do not show it while streaming or screen-sharing.",
                centerX,
                height - 44,
                0xFF9955);
    }

    private void drawQrCode(GuiGraphicsExtractor graphics, int centerX) {
        // A quiet zone (blank border) of at least 4 modules is required for a real scanner
        // to find the finder patterns reliably; the module scale is chosen so the whole
        // symbol, quiet zone included, comfortably fits above the warning text at default
        // GUI scale (this is exactly the "too-dense QR" failure mode the task brief warns
        // about: bigger modules, not more of them, is what actually helps a phone focus).
        int quietZoneModules = 4;
        int totalModules = qrCode.size + quietZoneModules * 2;
        int available = Math.min(width - 40, height - 130);
        int scale = Math.max(2, available / totalModules);
        int qrPixelSize = qrCode.size * scale;
        int originX = centerX - qrPixelSize / 2;
        int originY = 58;

        int quietPixels = quietZoneModules * scale;
        graphics.fill(
                originX - quietPixels,
                originY - quietPixels,
                originX + qrPixelSize + quietPixels,
                originY + qrPixelSize + quietPixels,
                0xFFFFFFFF);
        for (int y = 0; y < qrCode.size; y++) {
            for (int x = 0; x < qrCode.size; x++) {
                if (qrCode.getModule(x, y)) {
                    int px = originX + x * scale;
                    int py = originY + y * scale;
                    graphics.fill(px, py, px + scale, py + scale, 0xFF000000);
                }
            }
        }
    }

    private void drawAwaitingConfirmation(GuiGraphicsExtractor graphics, int centerX) {
        // FINDING M3b: name the remote IP. This is the only way a player can tell their own
        // phone apart from a shoulder-surfer or stream viewer who captured the QR code; a
        // prompt that reads the same either way cannot do the job the human gate exists for.
        String address = pendingPeerAddress != null ? pendingPeerAddress : "an unknown address";
        graphics.centeredText(
                font, "A device at " + address + " has scanned the code above", centerX, height / 2 - 30, 0xFFFFFF);
        graphics.centeredText(font, "and wants to pair with this game.", centerX, height / 2 - 18, 0xFFFFFF);
        graphics.centeredText(
                font,
                "Accepting lets it read your position and draw routes on your screen.",
                centerX,
                height / 2,
                0xCCCCCC);
        graphics.centeredText(
                font,
                "If this is not your device, click Decline.",
                centerX,
                height / 2 + 14,
                0xFF9955);
    }
}
