package app.seedscout.nav.protocol;

/**
 * Section 4.2's {@code clear} frame: remove the drawn route. Carries no fields, so it is
 * a singleton rather than a record with nothing in it.
 */
public final class ClearFrame implements InboundFrame {

    public static final ClearFrame INSTANCE = new ClearFrame();

    private ClearFrame() {
    }

    @Override
    public String type() {
        return "clear";
    }

    @Override
    public String toString() {
        return "ClearFrame";
    }
}
