package orbit.bot.level;

/**
 * The leveling mode's choice between quests and earning (docs/LEVELING.md «Стратег»): quests by default — they bring the
 * level, the money and the first equipment; farming when there is no quest the bot can do, or when the current goal
 * lacks a little money that farming earns clearly faster, then back to quests. A pure function: it switches nothing.
 */
public final class Strategy {

    /** Farming must beat the quests by this much to be chosen for money. */
    static final double FARM_BETTER = 1.2;
    /** A money gap farming closes within this long is worth leaving the quests for. */
    static final double FARM_HOURS_MAX = 1.0;

    private Strategy() {
    }

    public enum Mode { QUESTS, FARM }

    public record Decision(Mode mode, String config, String reason) {
    }

    /**
     * Earning per hour by what the account played (credits and uridium), measured by the advisor; {@code NaN}: not
     * measured yet.
     */
    public record Rates(double questCredits, double farmCredits, double questUridium, double farmUridium) {

        public static final Rates UNKNOWN = new Rates(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * @param faction    1 MMO, 2 EIC, 3 VRU ({@code 0} unknown: no decision)
     * @param mapName    the map the hero is on ({@code 1-3}…), where the quests led it
     * @param questsIdle the quest module has nothing it can do
     */
    public static Decision decide(AccountState s, LevelPlan plan, Advisor.Advice a, int faction, String mapName,
                                  boolean questsIdle, Rates r) {
        String tag = tag(faction);
        if (tag == null) {
            return new Decision(Mode.QUESTS, null, "фракция не известна — не решаю");
        }
        String quests = "Leveling " + tag + " Quests";
        String farm = "Leveling " + tag + " x-" + farmX(faction, mapName);
        if (questsIdle) {
            return new Decision(Mode.FARM, farm, "квестов, которые бот умеет, нет — фарм до следующего похода к квестодателю");
        }
        LevelPlan.Buy b = a.goal() == null ? null : a.goal().buy();
        if (b != null && a.missing() > 0 && a.purchase() == null) {
            long money = b.uridium() ? s.uridium() : s.credits();
            long reserve = b.uridium() ? plan.reserveUridium() : plan.reserveCredits();
            long gap = b.price() - Math.max(0, money - reserve);
            double farmRate = b.uridium() ? r.farmUridium() : r.farmCredits();
            double questRate = b.uridium() ? r.questUridium() : r.questCredits();
            String cur = b.uridium() ? "уридия" : "кредитов";
            if (gap > 0 && !Double.isNaN(farmRate) && !Double.isNaN(questRate) && farmRate > 0
                    && farmRate >= questRate * FARM_BETTER && gap / farmRate <= FARM_HOURS_MAX) {
                return new Decision(Mode.FARM, farm, String.format(java.util.Locale.ROOT,
                        "до %s не хватает %d %s: фарм %.0f/ч против квестов %.0f/ч — докопить за ~%.0f мин и обратно",
                        b.item(), gap, cur, farmRate, questRate, gap / farmRate * 60));
            }
            if (gap > 0 && (Double.isNaN(farmRate) || Double.isNaN(questRate))) {
                return new Decision(Mode.QUESTS, quests, "до " + b.item() + " не хватает " + gap + " " + cur
                        + "; заработок в час ещё не измерен — квесты");
            }
        }
        return new Decision(Mode.QUESTS, quests, "квесты — уровень, деньги и снаряжение");
    }

    /** The farm map's number on the own company's x-1…x-3 the quests led to; elsewhere x-1. */
    static int farmX(int faction, String mapName) {
        if (mapName != null && mapName.matches("\\d-\\d")) {
            int company = mapName.charAt(0) - '0';
            int x = mapName.charAt(2) - '0';
            if (company == faction && x >= 1 && x <= 3) {
                return x;
            }
        }
        return 1;
    }

    static String tag(int faction) {
        return switch (faction) {
            case 1 -> "MMO";
            case 2 -> "EIC";
            case 3 -> "VRU";
            default -> null;
        };
    }
}
