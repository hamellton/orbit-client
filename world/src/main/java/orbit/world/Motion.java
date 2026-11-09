package orbit.world;

/**
 * Straight-line movement from one point to another over a time span, as the client animates it.
 * Immutable; positions are evaluated for a given {@code System.nanoTime()} value.
 */
public record Motion(double fromX, double fromY, double toX, double toY, long startNanos, long durationNanos) {

    /** Standing still at a point. */
    public static Motion at(double x, double y, long nanos) {
        return new Motion(x, y, x, y, nanos, 0);
    }

    /** From the current position at {@code now} to a target, arriving after {@code durationMs}. */
    public Motion moveTo(long now, double targetX, double targetY, long durationMs) {
        return new Motion(x(now), y(now), targetX, targetY, now, Math.max(0, durationMs) * 1_000_000L);
    }

    /** From the current position at {@code now} to a target at a constant speed (units per second). */
    public Motion moveAtSpeed(long now, double targetX, double targetY, double speed) {
        double sx = x(now);
        double sy = y(now);
        return fromAtSpeed(sx, sy, targetX, targetY, speed, now);
    }

    /** From a known point to a target at a constant speed (units per second). */
    public static Motion fromAtSpeed(double x, double y, double targetX, double targetY, double speed, long now) {
        double dist = Math.hypot(targetX - x, targetY - y);
        long duration = speed > 0 ? (long) (dist / speed * 1e9) : 0;
        return new Motion(x, y, targetX, targetY, now, duration);
    }

    private double progress(long now) {
        if (durationNanos <= 0 || now >= startNanos + durationNanos) {
            return 1;
        }
        if (now <= startNanos) {
            return 0;
        }
        return (double) (now - startNanos) / durationNanos;
    }

    public double x(long now) {
        return fromX + (toX - fromX) * progress(now);
    }

    public double y(long now) {
        return fromY + (toY - fromY) * progress(now);
    }

    public boolean moving(long now) {
        return progress(now) < 1;
    }
}
