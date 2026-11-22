package orbit.world;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of the map around the hero at one moment ({@code nanos}, {@code System.nanoTime()}).
 * Positions of moving objects are interpolated to that moment.
 *
 * <p>{@code activatable}: map assets (portals, stations…) whose action the server offers now
 * ({@code MapAssetActionAvailableCommand}), id → since when; a portal becomes jumpable about
 * 300–500 units away. {@code jumpingTo}: map of a jump the server started, 0 if none.
 * {@code slotItems}: items on the slot bars (used with a key; others are clicked in the item bar).
 */
public record WorldSnapshot(long nanos, Hero hero, Map<Integer, Ship> ships, Map<String, Collectable> collectables,
                            Map<Integer, Portal> portals, Map<Integer, Asset> assets, Map<String, Zone> zones,
                            Group group, Map<String, Item> items, Inventory inventory, Pet pet,
                            Map<Integer, Long> activatable, int jumpingTo, Death death,
                            java.util.Set<String> slotItems, Map<Integer, Kill> destroyed, Rewards rewards,
                            List<Invitation> invitations, List<Contact> contacts, Map<String, Mine> mines,
                            Boosters boosters, JumpCpu jumpCpu, BoxCheck boxCheck, Quests quests, Damage damage,
                            Gates gates) {

    /** Without the galaxy gates (older callers and tests). */
    public WorldSnapshot(long nanos, Hero hero, Map<Integer, Ship> ships, Map<String, Collectable> collectables,
                         Map<Integer, Portal> portals, Map<Integer, Asset> assets, Map<String, Zone> zones,
                         Group group, Map<String, Item> items, Inventory inventory, Pet pet,
                         Map<Integer, Long> activatable, int jumpingTo, Death death,
                         java.util.Set<String> slotItems, Map<Integer, Kill> destroyed, Rewards rewards,
                         List<Invitation> invitations, List<Contact> contacts, Map<String, Mine> mines,
                         Boosters boosters, JumpCpu jumpCpu, BoxCheck boxCheck, Quests quests, Damage damage) {
        this(nanos, hero, ships, collectables, portals, assets, zones, group, items, inventory, pet, activatable, jumpingTo,
                death, slotItems, destroyed, rewards, invitations, contacts, mines, boosters, jumpCpu, boxCheck, quests,
                damage, Gates.NONE);
    }

    /**
     * One galaxy gate as the builder tells it ({@code GalaxyGateBuilderGateModule} and the commands after it): the builder's
     * gate number ({@code id}: α 1, β 2, γ 3, δ 4, ε 5, ζ 6, κ 7, λ 8, Kronos 12, Hades 13, Kuiper 19), {@code state}
     * (0 in progress, 1 finished), the parts collected ({@code parts}, {@code -1} not told), the wave on
     * ({@code wave}), the lives left and a life's price ({@code -1} when the gate is not set up), the multiplier;
     * {@code completedNanos} — when the server last said the gate was completed ({@code GalaxyGateBuilderGateCompleteCommand}),
     * 0 never.
     */
    public record Gate(int id, int state, String mode, int parts, int wave, int lives, int lifePrice, int multiplier,
                       long completedNanos) {

        public Gate withParts(int p) {
            return new Gate(id, state, mode, p, wave, lives, lifePrice, multiplier, completedNanos);
        }

        public Gate withWave(int w) {
            return new Gate(id, state, mode, parts, w, lives, lifePrice, multiplier, completedNanos);
        }

        public Gate withLives(int l, int price) {
            return new Gate(id, state, mode, parts, wave, l, price, multiplier, completedNanos);
        }

        public Gate completed(int part, long nanos) {
            return new Gate(id, state, mode, part, wave, -1, -1, multiplier, nanos);
        }

        public static Gate of(int id) {
            return new Gate(id, 0, "", -1, 0, -1, -1, 1, 0);
        }
    }

    /**
     * The galaxy gate builder ({@code ggBuilder}): the gates, the uridium it shows ({@code money}), its samples, the energy
     * a spin costs, the spin amounts offered and the one chosen; the last answers to a gate set-up (prepare the jump) and
     * a spin — their error codes ({@code 0} none, {@code 1} not enough money) and when they came ({@code 0} never).
     * Kept over map changes: the gates are the account's.
     */
    public record Gates(Map<Integer, Gate> gates, double money, double samples, int energyCost, List<Integer> spinAmounts,
                        int spinSelected, long infoNanos, int setupGate, int setupError, long setupNanos, int spinError,
                        long spinNanos, Trinity trinity, Eternal eternal, Low low, Escort escort, Dispatch dispatch,
                        Chromin chromin) {

        public static final Gates NONE = new Gates(Map.of(), -1, -1, 0, List.of(), 0, 0, 0, 0, 0, 0, 0, Trinity.NONE,
                Eternal.NONE, Low.NONE, Escort.NONE, Dispatch.NONE, Chromin.NONE);

        public Gates(Map<Integer, Gate> gates, double money, double samples, int energyCost, List<Integer> spinAmounts,
                     int spinSelected, long infoNanos, int setupGate, int setupError, long setupNanos, int spinError,
                     long spinNanos, Trinity trinity, Eternal eternal, Low low, Escort escort, Dispatch dispatch) {
            this(gates, money, samples, energyCost, spinAmounts, spinSelected, infoNanos, setupGate, setupError, setupNanos,
                    spinError, spinNanos, trinity, eternal, low, escort, dispatch, Chromin.NONE);
        }

        public Gates(Map<Integer, Gate> gates, double money, double samples, int energyCost, List<Integer> spinAmounts,
                     int spinSelected, long infoNanos, int setupGate, int setupError, long setupNanos, int spinError,
                     long spinNanos, Trinity trinity, Eternal eternal, Low low, Escort escort) {
            this(gates, money, samples, energyCost, spinAmounts, spinSelected, infoNanos, setupGate, setupError, setupNanos,
                    spinError, spinNanos, trinity, eternal, low, escort, Dispatch.NONE);
        }

        public Gates(Map<Integer, Gate> gates, double money, double samples, int energyCost, List<Integer> spinAmounts,
                     int spinSelected, long infoNanos, int setupGate, int setupError, long setupNanos, int spinError,
                     long spinNanos, Trinity trinity, Eternal eternal, Low low) {
            this(gates, money, samples, energyCost, spinAmounts, spinSelected, infoNanos, setupGate, setupError, setupNanos,
                    spinError, spinNanos, trinity, eternal, low, Escort.NONE);
        }

        public Gates(Map<Integer, Gate> gates, double money, double samples, int energyCost, List<Integer> spinAmounts,
                     int spinSelected, long infoNanos, int setupGate, int setupError, long setupNanos, int spinError,
                     long spinNanos, Trinity trinity, Eternal eternal) {
            this(gates, money, samples, energyCost, spinAmounts, spinSelected, infoNanos, setupGate, setupError, setupNanos,
                    spinError, spinNanos, trinity, eternal, Low.NONE);
        }

        public Gates(Map<Integer, Gate> gates, double money, double samples, int energyCost, List<Integer> spinAmounts,
                     int spinSelected, long infoNanos, int setupGate, int setupError, long setupNanos, int spinError,
                     long spinNanos, Trinity trinity) {
            this(gates, money, samples, energyCost, spinAmounts, spinSelected, infoNanos, setupGate, setupError, setupNanos,
                    spinError, spinNanos, trinity, Eternal.NONE);
        }

        /** The gate by its builder number, {@code null} when not told. */
        public Gate gate(int id) {
            return gates.get(id);
        }
    }

    /**
     * The LoW group gate (docs/LOW.md) as the server tells it: the queue for its instance ({@code PlayerQueueRegistrationCommand}
     * after the jump into its portal: the gate and the place in the queue, when), and its plain texts (the client's
     * {@code groupgate_*} strings, 2026): «The LoW Gate has been brought online!», «Relay station activated», «Century Falcon
     * sighted», «Century Falcon called reinforcements», «You've completed the LoW Gate. Great job!», «Mission failed…»,
     * the instance cancelled («Not enough players on the map…», «You did not reach this Gate's required group size in
     * time!»): when each last came ({@code 0} never), how many relays and reinforcements since the login.
     */
    public record Low(int queueGate, int queuePosition, long queueNanos, long onlineNanos, long relayNanos, int relays,
                      long completedNanos, long bossNanos, int swarms, long failedNanos, long cancelledNanos) {

        public static final Low NONE = new Low(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public Low(int queueGate, int queuePosition, long queueNanos, long onlineNanos, long relayNanos, int relays,
                   long completedNanos) {
            this(queueGate, queuePosition, queueNanos, onlineNanos, relayNanos, relays, completedNanos, 0, 0, 0, 0);
        }

        public Low queued(int gate, int position, long nanos) {
            return new Low(gate, position, nanos, onlineNanos, relayNanos, relays, completedNanos, bossNanos, swarms, failedNanos, cancelledNanos);
        }

        public Low online(long nanos) {
            return new Low(queueGate, queuePosition, queueNanos, nanos, relayNanos, relays, completedNanos, bossNanos, swarms, failedNanos, cancelledNanos);
        }

        public Low relay(long nanos) {
            return new Low(queueGate, queuePosition, queueNanos, onlineNanos, nanos, relays + 1, completedNanos, bossNanos, swarms, failedNanos, cancelledNanos);
        }

        public Low completed(long nanos) {
            return new Low(queueGate, queuePosition, queueNanos, onlineNanos, relayNanos, relays, nanos, bossNanos, swarms, failedNanos, cancelledNanos);
        }

        public Low boss(long nanos) {
            return new Low(queueGate, queuePosition, queueNanos, onlineNanos, relayNanos, relays, completedNanos, nanos, swarms, failedNanos, cancelledNanos);
        }

        public Low swarm(long nanos) {
            return new Low(queueGate, queuePosition, queueNanos, onlineNanos, relayNanos, relays, completedNanos, bossNanos, swarms + 1, failedNanos, cancelledNanos);
        }

        public Low failed(long nanos) {
            return new Low(queueGate, queuePosition, queueNanos, onlineNanos, relayNanos, relays, completedNanos, bossNanos, swarms, nanos, cancelledNanos);
        }

        public Low cancelled(long nanos) {
            return new Low(queueGate, queuePosition, queueNanos, onlineNanos, relayNanos, relays, completedNanos, bossNanos, swarms, failedNanos, nanos);
        }
    }

    /**
     * The Chromin Rush event's chromin ({@code ChrominUpdateCommand}, DarkBot's {@code chrominEvent} facade): collected so
     * far and the event's cap (160 000 in the past events), when it last came ({@code 0} never). During the event the gates'
     * NPCs drop «Chromin Boxes» ({@code CHROMIN_BOX_*}).
     */
    public record Chromin(double current, double max, long nanos) {

        public static final Chromin NONE = new Chromin(0, 0, 0);

        /** The event is told and its cap not reached. */
        public boolean open() {
            return nanos != 0 && max > 0 && current < max;
        }
    }

    /**
     * The Eternal Blacklight gate (EBG) as the server tells it ({@code EternalBlacklight*Command}, sent at the login and as it
     * changes, docs/EBG.md): the CPUs that open its portal, the wave on and the furthest, the booster points and the three
     * offered ({@code type} 0 cooldown, 1 damage, 2 laser damage, 3 rocket damage, 4 laser hit chance, 5 hull, 6 shield,
     * 7 speed; {@code value} %), the boosters taken; when anything of it came ({@code 0} never).
     */
    public record Eternal(int cpus, int wave, int furthest, int points, List<int[]> options, List<int[]> taken, long nanos) {

        public static final Eternal NONE = new Eternal(-1, 0, 0, 0, List.of(), List.of(), 0);

        public boolean known() {
            return nanos != 0;
        }
    }

    /**
     * The dispatch window's retrievers ({@code DispatchGetRetrieverListCommand}: {@code statusType} 0 available, 1 in
     * progress; {@code DispatchSlotInfoCommand}), its last message ({@code DispatchMessageCommand}: 0 sent, 5 no resource,
     * 4 no slot, 8 send error…) and when each came ({@code 0} never).
     */
    public record Dispatch(List<Retriever> available, List<Retriever> inProgress, long availableNanos,
                           long inProgressNanos, int freeSlots, int maxSlots, long slotsNanos, int message,
                           long messageNanos) {

        public static final Dispatch NONE = new Dispatch(List.of(), List.of(), 0, 0, -1, -1, 0, -1, 0);
    }

    /**
     * A retriever: its number (R-01 = 13 … the client's DispatchRetrieverDefinition), its slot (0 available, 1–5 in
     * progress) and its duration in seconds (the time left for one in progress, as of when the list came).
     */
    public record Retriever(int id, int slotId, double duration) {
    }

    /**
     * The Mimesis escort (docs/MIMESIS.md) as the server tells it: {@code PayloadEscortInfoCommand} at each login and map
     * load, {@code PayloadEscortKeyInfoCommand}, {@code PayloadEscortTimeInfoCommand} (seconds from when it came: to the
     * next freighter, to the entry's close — 299 at the full hour, to the run's end — 2699), {@code PayloadEscortScoreCommand}.
     *
     * @param keys       Escort Keys, {@code -1} not told yet
     * @param timeNanos  when the times came ({@code 0} never)
     * @param scoreNanos when the score last came ({@code 0} never)
     */
    public record Escort(int keys, boolean inSession, double nextSpawn, double gateClose, double sessionEnd, long timeNanos,
                         int totalScore, int runScore, long scoreNanos, long nanos) {

        public static final Escort NONE = new Escort(-1, false, 0, 0, 0, 0, 0, 0, 0, 0);

        public boolean known() {
            return nanos != 0;
        }

        public boolean timesKnown() {
            return timeNanos != 0;
        }

        /** Seconds left of a time told at {@code timeNanos}, at {@code now} (never below 0). */
        public double left(double told, long now) {
            return Math.max(0, told - (now - timeNanos) / 1e9);
        }

        public Escort withKeys(int k, long at) {
            return new Escort(k, inSession, nextSpawn, gateClose, sessionEnd, timeNanos, totalScore, runScore, scoreNanos, at);
        }

        public Escort withTimes(double next, double close, double end, long at) {
            return new Escort(keys, inSession, next, close, end, at, totalScore, runScore, scoreNanos, at);
        }

        public Escort withScore(int total, int run, long at) {
            return new Escort(keys, inSession, nextSpawn, gateClose, sessionEnd, timeNanos, total, run, at, at);
        }

        public Escort withSession(boolean in, int total, long at) {
            return new Escort(keys, in, nextSpawn, gateClose, sessionEnd, timeNanos, total, runScore, scoreNanos, at);
        }
    }

    /**
     * Trinity Trials' window ({@code TrinityTrialsGateWindowCommand}): the attribute shown, the maps offered
     * ({@code openAttributes}: {@code param0} the map, {@code param1} "1" free), the difficulty open up to, the key's loot
     * and how many there are, a free entry; when it came ({@code 0} never).
     */
    public record Trinity(int attribute, List<String[]> offered, int openDifficulty, String lootId, int keys, boolean free,
                          long nanos) {

        public static final Trinity NONE = new Trinity(0, List.of(), 0, "", 0, false, 0);
    }

    /** Without the damage tally (older callers and tests). */
    public WorldSnapshot(long nanos, Hero hero, Map<Integer, Ship> ships, Map<String, Collectable> collectables,
                         Map<Integer, Portal> portals, Map<Integer, Asset> assets, Map<String, Zone> zones,
                         Group group, Map<String, Item> items, Inventory inventory, Pet pet,
                         Map<Integer, Long> activatable, int jumpingTo, Death death,
                         java.util.Set<String> slotItems, Map<Integer, Kill> destroyed, Rewards rewards,
                         List<Invitation> invitations, List<Contact> contacts, Map<String, Mine> mines,
                         Boosters boosters, JumpCpu jumpCpu, BoxCheck boxCheck, Quests quests) {
        this(nanos, hero, ships, collectables, portals, assets, zones, group, items, inventory, pet, activatable, jumpingTo,
                death, slotItems, destroyed, rewards, invitations, contacts, mines, boosters, jumpCpu, boxCheck, quests,
                Damage.NONE);
    }

    /** A recipe's state in the assembly ({@code AssemblyRecipeContentModule}, {@code AssemblyRecipeStatusUpdateCommand}). */
    public enum RecipeState { AVAILABLE, UNAVAILABLE, BUILDING, READY, OTHER }

    /**
     * An assembly recipe as the server last told it: its state, the batch being built or ready ({@code amount}), when
     * the build is done ({@code readyNanos}, building only) and why it is unavailable ({@code unavailable}, the
     * client's {@code RECIPEUNAVAILABLETYPE_*}: 0 the item is owned at its maximum, 1/2 the recipe used up).
     */
    public record Recipe(String id, RecipeState state, double amount, long readyNanos, int unavailable) {
    }

    /**
     * The damage the hits ({@code AttackHitCommand}) told since the map was entered, summed: the hero's on each
     * target ({@code dealt}), everyone's on each target ({@code received}, as far as the server sends others' hits)
     * and each attacker's on the hero ({@code taken}). Ids are the ships'; the hero is in none of the keys of
     * {@code dealt} and {@code received} as a victim of its own.
     */
    public record Damage(Map<Integer, Long> dealt, Map<Integer, Long> received, Map<Integer, Long> taken) {

        public static final Damage NONE = new Damage(Map.of(), Map.of(), Map.of());

        public long dealtTo(int id) {
            return dealt.getOrDefault(id, 0L);
        }

        public long receivedBy(int id) {
            return received.getOrDefault(id, 0L);
        }

        public long takenFrom(int id) {
            return taken.getOrDefault(id, 0L);
        }
    }

    /** Without quests (older callers and tests). */
    public WorldSnapshot(long nanos, Hero hero, Map<Integer, Ship> ships, Map<String, Collectable> collectables,
                         Map<Integer, Portal> portals, Map<Integer, Asset> assets, Map<String, Zone> zones,
                         Group group, Map<String, Item> items, Inventory inventory, Pet pet,
                         Map<Integer, Long> activatable, int jumpingTo, Death death,
                         java.util.Set<String> slotItems, Map<Integer, Kill> destroyed, Rewards rewards,
                         List<Invitation> invitations, List<Contact> contacts, Map<String, Mine> mines,
                         Boosters boosters, JumpCpu jumpCpu, BoxCheck boxCheck) {
        this(nanos, hero, ships, collectables, portals, assets, zones, group, items, inventory, pet, activatable, jumpingTo,
                death, slotItems, destroyed, rewards, invitations, contacts, mines, boosters, jumpCpu, boxCheck, Quests.NONE,
                Damage.NONE);
    }

    /**
     * The game's box check ({@code CaptchaTriggerCommand}, live 2026-11-12 on gb2): pick some or all of the
     * black or red boxes laid round the ship within the time; the ship is held inside the circle meanwhile. Solved —
     * {@code CaptchaResolvedCommand}, failed — {@code CaptchaExplodeCommand}. {@code null} when there is none.
     *
     * @param type    {@code CaptchaTriggerCommand.ECAPTCHATYPE_*}: some black, all black, some red, all red
     * @param amount  how many to pick (the «some» kinds)
     * @param seconds the time given
     * @param black   black boxes laid out (indexes 0…black−1), {@code red} red ones (the indexes after them)
     * @param boxes   the boxes as the client lays them out ({@code BoxCaptchaTrait.addBoxes}); the server only
     *                hears the index of each one picked ({@code CaptchaCollectRequest})
     */
    public record BoxCheck(int type, int amount, int seconds, int x, int y, int radius, int black, int red, long sinceNanos,
                           List<CheckBox> boxes) {

        public BoxCheck(int type, int amount, int seconds, int x, int y, int radius, int black, int red, long sinceNanos) {
            this(type, amount, seconds, x, y, radius, black, red, sinceNanos, List.of());
        }

        /** One box of the check: its index for the server, its colour, where the client put it, picked or not. */
        public record CheckBox(int index, boolean red, int x, int y, boolean picked) {
        }

        /** «All» kinds (types 1, 3). */
        public boolean all() {
            return type == 1 || type == 3;
        }

        /** How many boxes of the asked colour are still to pick (0 = done, waiting for the server). */
        public int left() {
            long picked = boxes.stream().filter(b -> b.picked() && b.red() == pickRed()).count();
            int want = all() ? (pickRed() ? red : black) : amount;
            return (int) Math.max(0, want - picked);
        }

        /** The boxes of the asked colour not picked yet. */
        public List<CheckBox> targets() {
            return boxes.stream().filter(b -> !b.picked() && b.red() == pickRed()).toList();
        }

        /** Red boxes to pick (types 2, 3), else black ones. */
        public boolean pickRed() {
            return type >= 2;
        }

        /** Seconds left, never below 0. */
        public long secondsLeft(long nowNanos) {
            return Math.max(0, seconds - (nowNanos - sinceNanos) / 1_000_000_000L);
        }

        /** The task in Russian: collect 3 black boxes, or collect all red boxes. */
        public String task() {
            String colour = pickRed() ? "красн" : "чёрн";
            if (all()) {
                return "соберите все " + colour + "ые ящики (" + (pickRed() ? red : black) + ")";
            }
            return "соберите " + amount + " " + colour + (amount % 10 == 1 && amount % 100 != 11 ? "ый ящик" : "ых ящика")
                    + " из " + (pickRed() ? red : black);
        }

        /** The check line in Russian: the task and the seconds left. */
        public String text(long nowNanos) {
            return "ПРОВЕРКА: " + task() + (seconds > 0 ? ", осталось " + secondsLeft(nowNanos) + " с" : "");
        }
    }

    /**
     * The jump CPU (live 2026-11-11, ru1): {@code CPUInitializationCommand.advancedJumpCPUEquipped}; vouchers
     * {@code 0|A|JV|n}; the price table {@code 0|A|JCPU|I|<price>|<maps;>|…} (uridium, -1 = not there) after
     * {@code JCPU|GET}; {@code 0|A|JCPU|C|<map>|<price>|…} confirms a chosen map; {@code 0|A|JCPU|S|<s>|<map>}
     * starts the countdown. The server takes a voucher while there are any, uridium after.
     *
     * @param vouchers     jump vouchers, -1 before the server says
     * @param prices       map → uridium price; -1 = not reachable by jump
     * @param confirmedMap the map the server confirmed for the jump, 0 = none (with its price and time)
     * @param countdownMap the map the countdown runs to, 0 = none (with its seconds and start)
     */
    public record JumpCpu(boolean equipped, int vouchers, Map<Integer, Integer> prices, long pricesNanos, int confirmedMap,
                          int confirmedPrice, long confirmedNanos, int countdownMap, int countdownSeconds,
                          long countdownNanos) {

        public static final JumpCpu NONE = new JumpCpu(false, -1, Map.of(), 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Boosters ({@code AttributeBoosterUpdateCommand}): the active ones by loot id ({@code booster_dmg-b01})
     * and the total bonus per attribute ({@code DAMAGE} → 13 %). {@code known} once the server has sent them.
     */
    public record Boosters(boolean known, Map<String, Booster> active, Map<String, Double> totals) {
        public static final Boosters NONE = new Boosters(false, Map.of(), Map.of());
    }

    /**
     * One booster: the player's own ones have a time left ({@code secondsLeft} counted from {@code nanos}),
     * equipment ones an amount (lasers that boost), shared ones a count.
     */
    public record Booster(String lootId, String kind, int secondsLeft, long nanos, int amount) {
        /** Seconds left at a moment; -1 for boosters without a time (equipment, shared, station). */
        public long secondsLeftAt(long now) {
            return secondsLeft < 0 ? -1 : Math.max(0, secondsLeft - (now - nanos) / 1_000_000_000L);
        }
    }

    /** A mine on the map ({@code AddMineCommand}); the client knows its type, not whose it is. */
    public record Mine(String hash, int typeId, int x, int y) {
    }

    /** An entry of the contact list ({@code ContactListInitializationCommand} and its updates). */
    public record Contact(int id, String name, String clanTag, boolean online, boolean friend, boolean clanMember) {
    }

    /**
     * A group invitation ({@code InvitationInitializationCommand}): {@code incoming} when the hero is the
     * candidate, else one the hero (or the group) sent.
     */
    public record Invitation(int inviterId, String inviterName, int candidateId, String candidateName, boolean incoming) {
    }

    /**
     * What the server's reward log ({@code 0|LM|ST|…}) has granted since the world model started:
     * currencies and points, and items by id (resources, ammunition, rockets, mines, extra energy, log disks).
     */
    public record Rewards(double credits, double uridium, double honor, double experience, Map<String, Double> items,
                          double spentCredits, double spentUridium) {

        public static final Rewards NONE = new Rewards(0, 0, 0, 0, Map.of());

        /**
         * Without what was spent. {@code spentCredits}, {@code spentUridium}: every fall of the balance (ammunition,
         * boosters, paid repairs, a gate's lives and spins, the Skylab's quick transport…).
         */
        public Rewards(double credits, double uridium, double honor, double experience, Map<String, Double> items) {
            this(credits, uridium, honor, experience, items, 0, 0);
        }

        /** What came since {@code base} (a reset of the window's statistics). */
        public Rewards minus(Rewards base) {
            if (base == null || base == NONE) {
                return this;
            }
            Map<String, Double> left = new java.util.LinkedHashMap<>();
            items.forEach((k, v) -> {
                double d = v - base.items().getOrDefault(k, 0.0);
                if (d > 0) {
                    left.put(k, d);
                }
            });
            return new Rewards(Math.max(0, credits - base.credits), Math.max(0, uridium - base.uridium),
                    Math.max(0, honor - base.honor), Math.max(0, experience - base.experience), Map.copyOf(left),
                    Math.max(0, spentCredits - base.spentCredits), Math.max(0, spentUridium - base.spentUridium));
        }
    }

    /**
     * A ship the server reported destroyed ({@code ShipDestroyedCommand}) in the last
     * {@code World.KILL_MEMORY_NANOS}: unlike a ship that just left the view, it is really dead.
     */
    public record Kill(int id, String name, int x, int y, long nanos) {
    }

    /** The player's own ship. {@code id == 0} until {@code ShipInitializationCommand} arrives. */
    /**
     * The player's own ship. {@code id == 0} until {@code ShipInitializationCommand} arrives.
     *
     * @param cargoFree    free cargo space ({@code ShipInitializationCommand.cargoSpace}: it grows when ore is sold)
     * @param config       selected configuration (1 or 2), 0 if unknown
     * @param enemyWarning number of enemy players the client warns about ({@code 0|n|w}), -1 if unknown
     * @param selectedId     ship selected as target (may be selected without shooting), 0 if none
     * @param attackTargetId ship the hero shoots lasers at ({@code AttackLaserRunCommand}), 0 if none
     * @param attackers      ships shooting at the hero now or that hit it in the last 5 seconds
     * @param minimapScale   the client's minimap zoom; a minimap click lands on a grid of
     *                       {@code 10 × minimapScale × map scale} units ({@code MiniMap.handleMouseDown})
     * @param formation      drone formation id ({@code DroneFormationChangeCommand}), -1 before the server says;
     *                       {@code f-11-he} is 11 (seen in recordings)
     * @param formationNanos when the server last reported it
     */
    public record Hero(int id, String name, String shipType, int mapId, int x, int y, boolean moving, int targetX,
                       int targetY, int speed, Health health, int factionId, int clanId, int level, double credits,
                       double uridium, double experience, double honor, int cargoFree, int cargoMax, boolean premium, boolean cloaked, boolean alive,
                       int selectedId, int attackTargetId, List<Integer> attackers, int petId, int config,
                       int enemyWarning, int minimapScale, Beacon beacon, int formation, long formationNanos) {
    }

    /**
     * An entry of the item bar: ammunition, rockets, mines, CPUs, techs, abilities…
     *
     * @param category    {@code MenuInitializationCommand} category, e.g. {@code lasers}, {@code rockets}
     * @param counter     amount (ammunition) or charges
     * @param timerState    {@code TimerStateModule} value (ready, active, cooling down)
     * @param timerTime     remaining milliseconds when received (the client feeds it to a flash {@code Timer})
     * @param timerNanos    when the timer was received
     * @param cooldownGroup {@code CooldownTypeModule} value shared with other items, -1 if none;
     *                      {@code MenuItemCooldownGroupTimerCommand} restarts the timer of the whole group
     */
    public record Item(String id, String category, double counter, double maxCounter, boolean selected,
                       boolean available, boolean activatable, boolean buyable, boolean blocked, boolean visible,
                       int timerState, double timerTime, double timerMax, long timerNanos, int cooldownGroup) {

        /** Milliseconds until the item is ready again (active effect or cooldown), 0 when ready. */
        public long remainingMs(long nowNanos) {
            if (timerState == orbit.protocol.gen.TimerStateModule.TIMERSTATE_READY || timerTime <= 0) {
                return 0;
            }
            return Math.max(0, (long) timerTime - (nowNanos - timerNanos) / 1_000_000);
        }
    }

    /**
     * What the hero carries outside the item bar.
     *
     * @param ores       ore type ({@code OreTypeModule}) → amount, from {@code AttributeOreCountUpdateCommand}
     * @param resources  loot id → amount, from {@code InventoryItemUpdatedCommand}
     * @param bootyKeys  legacy key code ({@code BK}, {@code BKB}, …) → count
     */
    /**
     * @param tradePrices ore → price from the last trade window ({@code TradeReplyCommand}: the ores it buys), with
     * @param tradeNanos  when it came (0 never)
     * @param petTradeNanos when the pet's trade pod opened the trade window ({@code PetTradeWindowShowCommand})
     */
    /**
     * @param oresNanos  when the server last listed the hold's ores; {@code loginNanos} when the ship came into the game
     *                   (its {@code ShipInitializationCommand}): ores listed before it may be another ship's
     */
    public record Inventory(Map<Integer, Double> ores, Map<String, Double> resources, Map<String, Integer> bootyKeys,
                            Map<Integer, Integer> tradePrices, long tradeNanos, long petTradeNanos,
                            Map<String, Recipe> recipes, Lab lab, long oresNanos, long loginNanos) {

        public Inventory(Map<Integer, Double> ores, Map<String, Double> resources, Map<String, Integer> bootyKeys,
                         Map<Integer, Integer> tradePrices, long tradeNanos, long petTradeNanos,
                         Map<String, Recipe> recipes, Lab lab) {
            this(ores, resources, bootyKeys, tradePrices, tradeNanos, petTradeNanos, recipes, lab, 0, 0);
        }

        /** The ores listed are this ship's: listed since it came into the game. */
        public boolean oresSinceLogin() {
            return oresNanos >= loginNanos;
        }

        public Inventory(Map<Integer, Double> ores, Map<String, Double> resources, Map<String, Integer> bootyKeys,
                         Map<Integer, Integer> tradePrices, long tradeNanos, long petTradeNanos) {
            this(ores, resources, bootyKeys, tradePrices, tradeNanos, petTradeNanos, Map.of(), Lab.NONE);
        }

        public Inventory(Map<Integer, Double> ores, Map<String, Double> resources, Map<String, Integer> bootyKeys,
                         Map<Integer, Integer> tradePrices, long tradeNanos, long petTradeNanos,
                         Map<String, Recipe> recipes) {
            this(ores, resources, bootyKeys, tradePrices, tradeNanos, petTradeNanos, recipes, Lab.NONE);
        }

        /** How much of an ore ({@code OreTypeModule} number) lies in the hold. */
        public long ore(int type) {
            Double v = ores.get(type);
            return v == null ? 0 : Math.round(v);
        }

        /** Xenomit does not take room in the hold (cargoSpace = max − the other ores, both recordings agree). */
        public static final int XENOMIT = 3;

        /** Ore in the hold that takes room. */
        public long cargoUsed() {
            return Math.round(ores.entrySet().stream().filter(e -> e.getKey() != XENOMIT).mapToDouble(Map.Entry::getValue).sum());
        }
    }

    /** One lab item's enrichment: the ore ({@code OreTypeModule} number) and how much of it is left (10 a unit of ore). */
    public record Enrichment(int ore, long left) {
    }

    /**
     * The ship's enrichment from the lab ({@code LabUpdateItemCommand}): {@link #LASER}, {@link #ROCKETS}, {@link #DRIVE},
     * {@link #SHIELD} — and when the server told it (0 never).
     */
    public record Lab(Map<Integer, Enrichment> items, long nanos) {
        public static final Lab NONE = new Lab(Map.of(), 0);
        public static final int LASER = 0;
        public static final int ROCKETS = 1;
        public static final int DRIVE = 2;
        public static final int SHIELD = 3;
        /** A unit of ore gives this much enrichment (recordings 2026-11-11/12: 1 000 seprom → +10 000, 7 000 promerium → +70 000). */
        public static final int PER_ORE = 10;
    }

    /**
     * Free room in the hold now: the capacity less the ore in it (-1 before the game said). Ores listed before the ship
     * came into the game are another ship's (a ship change: the quest ship's 20 000 seprom with the Skylab ship's
     * capacity, live 2026-11-18 on gb2 — 14 192 ordered into a hold of 8 000): then the free room the login told.
     */
    public long cargoFree() {
        return hero.cargoMax() < 0 || inventory.ores().isEmpty() || !inventory.oresSinceLogin() ? hero.cargoFree()
                : hero.cargoMax() - inventory.cargoUsed();
    }

    /**
     * The hero's own P.E.T.
     *
     * @param owned   the account has a pet ({@code PetInitializationCommand.hasPet})
     * @param active  launched and on the map ({@code PetHeroActivationCommand} … {@code PetDeactivationCommand})
     * @param gear    selected mode ({@code PetGearTypeModule}, e.g. {@code PETGEARTYPE_GUARD}), -1 if unknown
     * @param fuel    current fuel, -1 if unknown
     * @param heat    overheating of the combo gears, -1 if unknown
     * @param gearNanos when the server last named the mode ({@code PetGearSelectCommand}; it comes 12–32 s late
     *                  after a change, at once when the server sets Guard itself), 0 never
     * @param gears   the modes the pet has ({@code PetGearAddCommand} after a launch)
     * @param repairCost what repairing the destroyed pet costs ({@code PetUIRepairButtonCommand}), -1 unknown or
     *                   not offered
     * @param comboRepair the combo ship repair is working now ({@code PetBuffCommand} effect 6)
     * @param locatorX where the enemy locator last pointed ({@code PetGearLocatorUpdate}: its target, every ~2 s
     *                 while it finds one; recorded 2026-11-11, the points led to the Cubikon), with
     * @param locatorNanos when — 0 none on this map
     * @param locatorTargets the NPCs the enemy locator may be set to ({@code PetLocatorGearInitializationCommand}: only
     *                       those on the map), with {@code locatorTargetsNanos} when; 0 none on
     *                       this map yet
     * @param locatorNoneNanos when the locator last said «nothing» (its point in the map's corner, -1/-1): its target is
     *                         not on this map now; 0 never
     * @param hpLink   the HP Link is working now ({@code VisualModifierCommand} HP_LINK 86 on the pet or the hero — DarkBot's
     *                 «effect 326»)
     * @param hpLinkReadyNanos the HP Link gear's cooldown ends then ({@code PetBuffCommand} buff 9 PET_HP_LINK_GEAR with
     *                         its seconds; removed — ready), 0 ready
     */
    public record Pet(boolean owned, boolean alive, boolean hasFuel, boolean active, int id, String name, int level,
                      Health health, int fuel, int fuelMax, double heat, double heatMax, int gear, long gearNanos,
                      java.util.Set<Integer> gears, int repairCost, boolean comboRepair, int locatorX, int locatorY,
                      long locatorNanos, long locatorNoneNanos, java.util.List<Integer> locatorTargets,
                      long locatorTargetsNanos, boolean hpLink, long hpLinkReadyNanos) {

        static final Pet NONE = new Pet(false, false, false, false, 0, "", 0, Health.UNKNOWN, -1, -1, -1, -1, -1, 0,
                java.util.Set.of(), -1, false, 0, 0, 0, 0, java.util.List.of(), 0);

        public Pet(boolean owned, boolean alive, boolean hasFuel, boolean active, int id, String name, int level,
                   Health health, int fuel, int fuelMax, double heat, double heatMax, int gear, long gearNanos,
                   java.util.Set<Integer> gears, int repairCost, boolean comboRepair, int locatorX, int locatorY,
                   long locatorNanos, long locatorNoneNanos, java.util.List<Integer> locatorTargets,
                   long locatorTargetsNanos) {
            this(owned, alive, hasFuel, active, id, name, level, health, fuel, fuelMax, heat, heatMax, gear, gearNanos, gears,
                    repairCost, comboRepair, locatorX, locatorY, locatorNanos, locatorNoneNanos, locatorTargets,
                    locatorTargetsNanos, false, 0);
        }

        /** The same with the HP Link's state. */
        public Pet withHpLink(boolean on, long readyNanos) {
            return new Pet(owned, alive, hasFuel, active, id, name, level, health, fuel, fuelMax, heat, heatMax, gear,
                    gearNanos, gears, repairCost, comboRepair, locatorX, locatorY, locatorNanos, locatorNoneNanos,
                    locatorTargets, locatorTargetsNanos, on, readyNanos);
        }

        /** The HP Link gear may be used at {@code nowNanos}: not on its cooldown. */
        public boolean hpLinkReady(long nowNanos) {
            return hpLinkReadyNanos == 0 || nowNanos >= hpLinkReadyNanos;
        }

        /** The locator's point is this fresh (ms) or newer. */
        public boolean locatorWithin(long nowNanos, long ms) {
            return locatorNanos != 0 && nowNanos - locatorNanos <= ms * 1_000_000;
        }

        /** The locator said «nothing» this fresh (ms) or newer, and found nothing since. */
        public boolean locatorNoneWithin(long nowNanos, long ms) {
            return locatorNoneNanos != 0 && locatorNoneNanos >= locatorNanos && nowNanos - locatorNoneNanos <= ms * 1_000_000;
        }

        public Pet {
            gears = gears == null ? java.util.Set.of() : java.util.Set.copyOf(gears);
        }
    }

    /**
     * The kill screen ({@code KillScreenPostCommand}): who destroyed the hero and how it can be
     * repaired. {@code null} while the ship is alive.
     */
    public record Death(String killer, int cause, List<RepairOption> options) {

        /** The first option that costs nothing and is available now, or {@code null}. */
        public RepairOption freeRepair() {
            return options.stream().filter(o -> o.affordable() && o.price() == 0 && o.cooldownSeconds() == 0)
                    .findFirst().orElse(null);
        }
    }

    /**
     * One way to come back ({@code KillScreenOptionModule}).
     *
     * @param type      {@code KillScreenOptionTypeModule} value, e.g. {@code OPTIONTYPE_BASIC_REPAIR}
     * @param priceType {@code PriceModule.type} (currency), {@code price} its amount
     */
    public record RepairOption(int type, int priceType, int price, boolean affordable, int cooldownSeconds, String button) {
    }

    /** Area flags from the last {@code BeaconCommand} (sent about once a second). */
    public record Beacon(boolean inRadiationZone, boolean inStation, boolean inPeaceArea, boolean nearJumpGate,
                         boolean repairRobotActive) {

        static final Beacon NONE = new Beacon(false, false, false, false, false);
    }

    public enum ShipKind { PLAYER, NPC, PET }

    /**
     * Another ship, NPC or pet.
     *
     * @param relation        {@code ClanRelationModule} type ({@code TYPE_ALLIED}, {@code TYPE_AT_WAR}, …)
     * @param attackTargetId  id it is shooting lasers at, or 0
     * @param ownerId         owner of a pet, 0 otherwise
     * @param lockedByOther   another player (not in the group) attacks it; the client shows it grey
     */
    public record Ship(int id, ShipKind kind, String name, String typeId, int factionId, int clanId, String clanTag,
                       int relation, boolean cloaked, int x, int y, boolean moving, int targetX, int targetY,
                       Health health, int attackTargetId, int ownerId, boolean lockedByOther, boolean invincible) {

        /** A ship without the invincibility shield. */
        public Ship(int id, ShipKind kind, String name, String typeId, int factionId, int clanId, String clanTag,
                    int relation, boolean cloaked, int x, int y, boolean moving, int targetX, int targetY,
                    Health health, int attackTargetId, int ownerId, boolean lockedByOther) {
            this(id, kind, name, typeId, factionId, clanId, clanTag, relation, cloaked, x, y, moving, targetX, targetY,
                    health, attackTargetId, ownerId, lockedByOther, false);
        }

        public boolean npc() {
            return kind == ShipKind.NPC;
        }

        public double distanceTo(int px, int py) {
            return Math.hypot(x - px, y - py);
        }
    }

    public enum CollectableKind { BOX, ORE }

    /**
     * A box or an ore field.
     *
     * @param boxType  box type name for boxes (e.g. {@code BONUS_BOX}), empty for ores
     * @param oreType  {@code OreTypeModule} value for ores, -1 for boxes
     */
    public record Collectable(String hash, CollectableKind kind, String boxType, int oreType, int x, int y) {
    }

    public record Portal(int id, int x, int y, int factionId, int designId, boolean visibleOnMinimap) {
    }

    /**
     * Station, turret, healing pod, battle station… ({@code AssetTypeModule} type).
     *
     * @param clanRelation the owner clan to the hero's ({@code ClanRelationModule}: 0 none, 1 allied, 2 non-aggression
     *                     pact, 3 at war)
     */
    public record Asset(int id, int type, String name, int x, int y, int factionId, int clanId, String clanTag,
                        boolean invisible, int clanRelation) {

        public Asset(int id, int type, String name, int x, int y, int factionId, int clanId, String clanTag,
                     boolean invisible) {
            this(id, type, name, x, y, factionId, clanId, clanTag, invisible, 0);
        }

        /** The client shows it friendly: the owner clan is allied or has a non-aggression pact with the hero's. */
        public boolean friendlyClan() {
            return clanRelation == 1 || clanRelation == 2;
        }
    }

    /**
     * Point of interest: radiation, no-access, healing and other zones.
     *
     * @param type   {@code POITypeModule} value
     * @param shape  {@code MapAddPOICommand.SHAPETYPE_CIRCLE} (coords x, y, radius), {@code SHAPETYPE_POLYGON}
     *               or {@code SHAPETYPE_RECTANGLE} (coords as x/y pairs), as the client's {@code MapAddPOIHandler} reads them
     * @param inverted the area outside the shape is the zone (e.g. the radiation border of a map)
     */
    public record Zone(String id, int type, int shape, List<Integer> coords, boolean inverted, boolean active) {
    }

    /**
     * A group member as the group packets tell him; {@code targetName}/{@code targetHealth} — what he has selected
     * ({@code TargetModule}: the client shows it in the group window), "" and {@link Health#UNKNOWN} when nothing;
     * {@code inGate}/{@code gateStage} — the raid gate as the server tells it per player ({@code RaidGateGameStageModule}): the
     * client's tooltips (2026) name it the <b>Hades Gate</b> stage («You and this player are at the same Hades Gate stage»),
     * so it says nothing about LoW.
     */
    public record Member(int id, String name, int level, int factionId, int mapId, int x, int y, Health health,
                         boolean loggedOut, boolean cloaked, String targetName, Health targetHealth, boolean inGate,
                         int gateStage) {

        public Member(int id, String name, int level, int factionId, int mapId, int x, int y, Health health,
                      boolean loggedOut, boolean cloaked, String targetName, Health targetHealth) {
            this(id, name, level, factionId, mapId, x, y, health, loggedOut, cloaked, targetName, targetHealth, false, 0);
        }

        public Member(int id, String name, int level, int factionId, int mapId, int x, int y, Health health,
                      boolean loggedOut, boolean cloaked) {
            this(id, name, level, factionId, mapId, x, y, health, loggedOut, cloaked, "", Health.UNKNOWN, false, 0);
        }

        Member with(Health h) {
            return new Member(id, name, level, factionId, mapId, x, y, h, loggedOut, cloaked, targetName, targetHealth, inGate, gateStage);
        }

        Member withTarget(String n, Health h) {
            return new Member(id, name, level, factionId, mapId, x, y, health, loggedOut, cloaked, n, h, inGate, gateStage);
        }

        Member at(int map, int nx, int ny) {
            return new Member(id, name, level, factionId, map, nx, ny, health, loggedOut, cloaked, targetName, targetHealth, inGate, gateStage);
        }

        Member level(int l) {
            return new Member(id, name, l, factionId, mapId, x, y, health, loggedOut, cloaked, targetName, targetHealth, inGate, gateStage);
        }

        Member out(boolean o) {
            return new Member(id, name, level, factionId, mapId, x, y, health, o, cloaked, targetName, targetHealth, inGate, gateStage);
        }

        Member cloak(boolean c) {
            return new Member(id, name, level, factionId, mapId, x, y, health, loggedOut, c, targetName, targetHealth, inGate, gateStage);
        }

        Member raid(boolean in, int stage) {
            return new Member(id, name, level, factionId, mapId, x, y, health, loggedOut, cloaked, targetName, targetHealth, in, stage);
        }
    }

    public record Group(int id, int leaderId, List<Member> members) {
        static final Group NONE = new Group(0, 0, List.of());

        public boolean exists() {
            return id != 0;
        }
    }

    /** The selected item of a category (e.g. the laser ammunition in use), or {@code null}. */
    public Item selected(String category) {
        return items.values().stream().filter(i -> i.selected() && category.equals(i.category())).findFirst().orElse(null);
    }

    /** Ships of the given kind sorted by distance to the hero, nearest first. */
    public List<Ship> nearest(ShipKind kind) {
        return ships.values().stream().filter(s -> s.kind() == kind)
                .sorted(Comparator.comparingDouble(s -> s.distanceTo(hero.x(), hero.y()))).toList();
    }
}
