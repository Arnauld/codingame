import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

// si submerger wind en bord de map pour virer les enemis ou wind vers heroes
//
class Player {


    static final double[] COS;
    static final double[] SIN;

    static {
        int n = (int) (Math.PI * 2 * 100);
        COS = new double[n];
        SIN = new double[n];

        double delta = (Math.PI * 2) / n;
        for (int i = 0; i < n; i++) {
            COS[i] = Math.cos(i * delta);
            SIN[i] = Math.sin(i * delta);
        }
    }

    static double sin(double angle) {
        int n = (int) (angle * 100);
        return SIN[n % SIN.length];
    }

    static double cos(double angle) {
        int n = (int) (angle * 100);
        return COS[n % COS.length];
    }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);

        int baseX = in.nextInt(); // base_x,base_y: The corner of the map representing your base
        int baseY = in.nextInt();
        int heroesPerPlayer = in.nextInt(); // heroesPerPlayer: Always 3

        Point baseLocation = new Point(baseX, baseY);
        System.err.println("base position " + baseLocation);
        Player player = new Player(baseLocation);
        int round = 0;
        // game loop
        while (true) {
            round++;
            Round gameState = readGameState(in, round, heroesPerPlayer);
            System.err.println("monsters: " + gameState.monsters.size());
            Map<Integer, Order> orders = player.newRound(gameState);

            // render orders in hero order
            for (Entity hero : gameState.myHeroes) {
                System.out.println(orders.get(hero.id).asString());
            }
        }
    }

    public static Round readGameState(Scanner in, int round, int heroesPerPlayer) {
        int myHealth = in.nextInt(); // Your base health
        int myMana = in.nextInt(); // Ignore in the first league; Spend ten mana to cast a spell
        int oppHealth = in.nextInt();
        int oppMana = in.nextInt();

        int entityCount = in.nextInt(); // Amount of heros and monsters you can see

        List<Entity> myHeroes = new ArrayList<>(heroesPerPlayer);
        List<Entity> oppHeroes = new ArrayList<>(heroesPerPlayer);
        List<Entity> monsters = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            int id = in.nextInt(); // Unique identifier
            int type = in.nextInt(); // 0=monster, 1=your hero, 2=opponent hero
            int x = in.nextInt(); // Position of this entity
            int y = in.nextInt();
            int shieldLife = in.nextInt(); // Ignore for this league; Count down until shield spell fades
            int isControlled = in.nextInt(); // Ignore for this league; Equals 1 when this entity is under a control
            // spell
            int health = in.nextInt(); // Remaining health of this monster
            int vx = in.nextInt(); // Trajectory of this monster
            int vy = in.nextInt();
            int nearBase = in.nextInt(); // 0=monster with no target yet, 1=monster targeting a base
            int threatFor = in.nextInt(); // Given this monster's trajectory, is it a threat to 1=your base, 2=your
            // opponent's base, 0=neither

            Entity entity = new Entity(
                    id, type, new Point(x, y), shieldLife, isControlled, health, new Vector(vx, vy), nearBase,
                    threatFor);
            switch (type) {
                case TYPE_MONSTER:
                    monsters.add(entity);
                    break;
                case TYPE_MY_HERO:
                    myHeroes.add(entity);
                    break;
                case TYPE_OP_HERO:
                    oppHeroes.add(entity);
                    break;
            }
        }
        return new Round(round, myHealth, myMana, oppHealth, oppMana, myHeroes, oppHeroes, monsters);
    }


    //
    //
    //
    static final int TYPE_MONSTER = 0;
    static final int TYPE_MY_HERO = 1;
    static final int TYPE_OP_HERO = 2;

    //
    static final int MANA_COST = 10;
    //
    static final int BASE_FOG_RADIUS = 6000;
    static final int BASE_THREAT_RADIUS = 5000;
    static final int MONSTER_MOVE = 400;

    static final int HERO_DAMAGE = 2;
    static final int HERO_DAMAGE_EFFECT_RANGE = 800;
    static final int HERO_MOVE = 800;
    static final int HERO_FOG_RADIUS = 2200;
    static final int CONTROL_EFFECT_RANGE = 2200;
    static final int WIND_EFFECT_RANGE = 1280;
    static final int WIND_MOVE = 2200;
    static final int SHIELD_EFFECT_RANGE = 2200;

    final WarField warField;
    int windBlowerId = -1;
    boolean windBlowerUnderControl = false;
    Set<Integer> underControlLastRound = new HashSet<>();

    Map<Integer, MonsterTrack> monsterCache = new HashMap<>();

    Player(Point basePosition) {
        this.warField = computeWarField(basePosition);
    }

    private Point basePosition() {
        return warField.basePosition;
    }

    private Point opponentBasePosition() {
        return warField.opponentBasePosition;
    }

    static final int MANA_RESERVE = 30;


    private Predicate<Entity> or(Predicate<Entity> p1, Predicate<Entity> p2) {
        return p1.or(p2);
    }


    // -----------------------------------------------------------------------------------------------------------------
    //                          __                       _
    //  _ __   _____      __   /__\ ___  _   _ _ __   __| |
    // | '_ \ / _ \ \ /\ / /  / \/// _ \| | | | '_ \ / _` |
    // | | | |  __/\ V  V /  / _  \ (_) | |_| | | | | (_| |
    // |_| |_|\___| \_/\_/   \/ \_/\___/ \__,_|_| |_|\__,_|
    //
    //
    // -----------------------------------------------------------------------------------------------------------------
    private Map<Integer, Order> newRound(Round round) {
        enrichData(round);

        List<Order> allOrders = new ArrayList<>();

        Entity windBlower = round.myHeroes.get(0);
        windBlowerId = windBlower.id;
        evaluateWindBlower(windBlower, allOrders, round);
        evaluateThreats(allOrders, round);
        evaluateIdle(allOrders, round);

        underControlLastRound.clear();

        round.myHeroes.forEach(hero ->
                allOrders.stream()
                        .filter(order -> order.heroId() == hero.id)
                        .sorted(Order::priorityComparator)
                        .forEach(o -> {
                            System.err.println("  " + o);
                        }));

        Map<Integer, Order> selectionByHero = new HashMap<>();
        round.myHeroes.forEach(hero -> {
            allOrders.stream()
                    .filter(order -> order.heroId() == hero.id)
                    .max(Order::priorityComparator)
                    .ifPresent(order -> {
                        selectionByHero.put(hero.id, order);
                        if (order instanceof Control) {
                            Control ctrl = (Control) order;
                            allOrders.removeIf(ctrl::isSimilarTo);
                            underControlLastRound.add(ctrl.entity.id);
                        } else if (order instanceof Wind) {
                            Wind wind = (Wind) order;
                            allOrders.removeIf(isControlTargetingOneOf(wind.entityIdsAffected()));
                        }
                    });
        });

        return selectionByHero;
    }

    static final Predicate<Order> isControlTargetingOneOf(Set<Integer> ids) {
        return o -> {
            if (o instanceof Control) {
                Control ctrl = (Control) o;
                return ids.contains(ctrl.entity.id);
            }
            return false;
        };
    }

    //  _     _ _
    // (_) __| | | ___
    // | |/ _` | |/ _ \
    // | | (_| | |  __/
    // |_|\__,_|_|\___|
    //
    private void evaluateIdle(List<Order> orders, Round round) {
        List<Point> sentinelPositions = new ArrayList<>(warField.baseSentinelPoints);
        round.myHeroes.forEach(hero -> {
            if (hero.id == windBlowerId)
                return;

            selectAround(hero.position, round.monsters, Entity::destination, HERO_FOG_RADIUS)
                    .findFirst()
                    .ifPresent(monster -> {
                        orders.add(new Move(hero, Priority.IDLE + 100, monster, monster.destination(), "hunting..."));
                    });

            // too close from base
            double heroToBaseDistance = basePosition().distanceTo(hero.position);
            if (heroToBaseDistance < 5 * HERO_MOVE) {
                Optional<Point> target = sentinelPositions.stream()
                        .sorted(hero::distanceToPointComparator)
                        .findFirst();
                if (target.isPresent()) {
                    Point pt = target.get();
                    sentinelPositions.remove(pt);
                    orders.add(new Move(hero, Priority.IDLE + 10, null, pt, "repositionning..."));
                } else {
                    orders.add(new Move(hero, Priority.IDLE + 10, null, basePosition(), "repositionning..."));
                }
            }

            // too far from base
            if (heroToBaseDistance > 7 * HERO_MOVE) {
                Optional<Point> target = sentinelPositions.stream()
                        .sorted(hero::distanceToPointComparator)
                        .findFirst();
                if (target.isPresent()) {
                    Point pt = target.get();
                    sentinelPositions.remove(pt);
                    orders.add(new Move(hero, Priority.IDLE + 300, null, pt, "repositionning..."));
                } else {
                    orders.add(new Move(hero, Priority.IDLE + 300, null, basePosition(), "repositionning..."));
                }
            }


            double angle = 2 * Math.PI * Math.random();
            orders.add(new Move(hero, Priority.IDLE - 10, null,
                    hero.position.translate(HERO_MOVE * cos(angle), HERO_MOVE * sin(angle))
                            .clamp(ORIGIN1, ORIGIN2),
                    "*sifflote*"));
        });
    }

    //  _   _                    _
    // | |_| |__  _ __ ___  __ _| |_
    // | __| '_ \| '__/ _ \/ _` | __|
    // | |_| | | | | |  __/ (_| | |_
    //  \__|_| |_|_|  \___|\__,_|\__|
    //
    private void evaluateThreats(List<Order> orders, Round round) {

        List<Entity> allThreats = round.monsters.stream()
                .filter(Entity::isThreat)
                .collect(toList());

        List<Entity> heroesIn = round.myHeroes.stream().filter(hero -> hero.id != windBlowerId).collect(toList());
        boolean enoughMana = round.myMana >= MANA_COST;
        int threatRadius = BASE_THREAT_RADIUS;
        int nbRoundInBase = BASE_THREAT_RADIUS / MONSTER_MOVE;

        // closest threat
        List<Entity> closeThreats = allThreats.stream()
                .filter(t -> t.distanceToBase < threatRadius)
                .sorted(Entity::distanceToBaseComparator)
                .collect(toList());

        if (closeThreats.size() > 0) {
            // can be killed using normal damage
            Map<Integer, Point> solution = attemptToKillThemAll(round, closeThreats, heroesIn);
            if (solution != null) {
                heroesIn.forEach(hero -> {
                    Point target = solution.get(hero.id);
                    if (target != null)
                        orders.add(new Move(hero,
                                Priority.MULTIPLE_THREATS + 700,
                                null,
                                target,
                                "Carnage!!"));
                });
            }
        }

        closeThreats.forEach(threat -> {
            int nbRoundRemainingMove = (int) (Math.ceil(threat.distanceToBase) / threat.rawSpeed());
            System.err.println("M" + threat.id + " :: remaining round: " + nbRoundRemainingMove);
            heroesIn.forEach(hero -> {
                orders.add(new Move(hero,
                        Priority.THREAT + (12 - nbRoundRemainingMove) * 100 + threat.health * 10,
                        null,
                        threat.position(),
                        "kill!"));
            });

            if (nbRoundRemainingMove < 4 && enoughMana) {
                heroesIn.forEach(hero -> {
                    if (hero.position.distanceTo(threat.position()) <= WIND_EFFECT_RANGE) {
                        orders.add(new Wind(hero,
                                Priority.EMERGENCY,
                                hero.position.translate(warField.baseEmergencyWindPush),
                                "no way!",
                                allThreats.stream().filter(th -> th.position().distanceTo(hero.position) <= WIND_EFFECT_RANGE).map(Entity::id).collect(toSet())));
                    }
                });
            }
        });

        // search most impacting hero
        AtomicReference<Entity> heroCandidate = new AtomicReference<>();
        heroesIn.forEach(hero -> {
            AtomicInteger nbInWind = new AtomicInteger(0);
            AtomicInteger nbInControl = new AtomicInteger(0);
            AtomicInteger nbInDamage = new AtomicInteger(0);
            closeThreats.forEach(threat -> {
                double distance = threat.position.distanceTo(hero.position);
                if (distance <= WIND_EFFECT_RANGE) {
                    nbInWind.incrementAndGet();
                }
                if (distance <= CONTROL_EFFECT_RANGE && round.myMana >= MANA_COST) {
                    orders.add(new Control(
                            hero,
                            Priority.THREAT + 700 / (1 + threat.nbRoundRemaining),
                            threat,
                            warField.nextRedirectPoint(threat),
                            "go away!"));
                }
                if (distance <= HERO_DAMAGE_EFFECT_RANGE) {
                    nbInDamage.incrementAndGet();
                }
            });


            if (enoughMana && nbInWind.get() > 0) {
                orders.add(new Wind(hero,
                        Priority.THREAT + 200 * nbInWind.get(),
                        hero.position.translate(warField.baseEmergencyWindPush),
                        "Woosh!",
                        entityIdsInWindRange(allThreats, hero.position())
                ));
            }
        });

        if (enoughMana && closeThreats.size() > 2 && heroCandidate.get() != null) {
            Entity hero = heroCandidate.get();
            orders.add(
                    new Wind(hero,
                            Priority.MULTIPLE_THREATS,
                            hero.position.translate(warField.baseEmergencyWindPush),
                            "Woosh mass!",
                            entityIdsInWindRange(allThreats, hero.position())
                    ));
        } else if (closeThreats.size() > 0) {
            round.myHeroes.forEach(hero -> {
                closeThreats.stream()
                        .min(hero::distanceToEntityComparator)
                        .ifPresent(threat -> {
                            orders.add(new Move(hero, Priority.THREAT + 500, threat, threat.position(), "Attack!!!"));
                        });
            });

        }
    }


    //           _           _   _     _
    // __      _(_)_ __   __| | | |__ | | _____      _____ _ __
    // \ \ /\ / / | '_ \ / _` | | '_ \| |/ _ \ \ /\ / / _ \ '__|
    //  \ V  V /| | | | | (_| | | |_) | | (_) \ V  V /  __/ |
    //   \_/\_/ |_|_| |_|\__,_| |_.__/|_|\___/ \_/\_/ \___|_|
    //
    private void evaluateWindBlower(Entity windBlower, List<Order> orders, Round round) {

        windBlowerUnderControl = windBlowerUnderControl || windBlower.isControlled();
        double distanceToOpponentBase = windBlower.position.distanceTo(opponentBasePosition());
        boolean enoughMana = round.myMana > (MANA_COST + MANA_RESERVE);
        List<Entity> controllableOpponentAround = selectAround(windBlower.position, round.oppHeroes, Entity::position, CONTROL_EFFECT_RANGE)
                .filter(Entity::isNotControlled)
                .collect(toList());

        Predicate<Entity> opponentThreat = Entity::isOpponentThreat;

        if (round.round < 50 || !enoughMana) {
            Optional<Entity> monsterOpt =
                    selectAround(windBlower.position, round.monsters.stream().filter(opponentThreat.negate()), Entity::position, 4 * HERO_MOVE)
                            .min(windBlower::distanceToEntityComparator);
            // Farming
            monsterOpt.ifPresent(entity -> orders.add(new Move(windBlower, Priority.WIND_BLOWER + 700, entity, entity.position(), "farming.")));
        }

        if (distanceToOpponentBase > 5000) {
            orders.add(new Move(windBlower, Priority.WIND_BLOWER + 300, null, opponentBasePosition(), "WindBlower!"));
        }

        if (enoughMana) {
            long nbMonsterAround = selectAround(windBlower.position, round.monsters, Entity::position, WIND_EFFECT_RANGE)
                    .filter(m -> m.shieldLife == 0)
                    .count();
            if (nbMonsterAround > 1) {
                System.err.print("\n\t: mass-blow!!");
                Point target = opponentBasePosition();
                orders.add(new Wind(
                        windBlower,
                        Priority.WIND_BLOWER + 600,
                        target,
                        "Mass Atchooom!!!",
                        round.monsters.stream().filter(e -> e.position().distanceTo(windBlower.position) <= WIND_EFFECT_RANGE).map(Entity::id).collect(toSet())
                ));
            }

            if (windBlowerUnderControl && !windBlower.hasShield() && controllableOpponentAround.size() > 0) {
                System.err.print("\n\t: bubulle!!");
                orders.add(new Shield(windBlower, Priority.WIND_BLOWER + 900, windBlower, "Bubulle!!!"));
            }

            if (distanceToOpponentBase > 5000 && controllableOpponentAround.size() > 2 && (round.myMana > MANA_RESERVE * 2)) {
                orders.add(new Wind(
                        windBlower,
                        Priority.WIND_BLOWER + 700,
                        basePosition(),
                        "Ecarto!!!",
                        round.monsters.stream().filter(e -> e.position().distanceTo(windBlower.position) <= WIND_EFFECT_RANGE).map(Entity::id).collect(toSet())
                ));
            }

            List<Entity> controllableMonstersAround = selectAround(windBlower.position, round.monsters, Entity::position, CONTROL_EFFECT_RANGE)
                    .filter(or(Entity::noThreat, Entity::isThreat))
                    .filter(m -> m.shieldLife == 0)
                    .filter(Entity::isNotControlled)
                    .collect(toList());

            if (controllableMonstersAround.size() > 0) {
                controllableMonstersAround.stream()
                        .filter(Entity::notUnderControlLastRound)
                        .max(Entity::healthComparator).ifPresent(monster -> {
                    orders.add(new Control(windBlower, Priority.WIND_BLOWER + 800, monster, warField.nextRedirectPoint(monster), "*redirect*"));
                });
            }

            if (distanceToOpponentBase < 5000) {
                selectAround(windBlower.position, round.monsters, Entity::position, SHIELD_EFFECT_RANGE)
                        .filter(Entity::isOpponentThreat)
                        .filter(Entity::isNotControlled)
                        .filter(e -> e.shieldLife == 0)
                        // will be killed next round ?
                        .filter(e -> e.health > HERO_DAMAGE * countAround(e.position, round.oppHeroes, Entity::position, HERO_DAMAGE_EFFECT_RANGE))
                        .min(opponentBasePosition()::distanceToHasPositionComparator)
                        .ifPresent(mob -> {
                            orders.add(new Shield(
                                    windBlower,
                                    Priority.WIND_BLOWER + 850,
                                    mob,
                                    "blessing mob"));
                        });
            }
        }


        round.monsters.stream()
                .min(windBlower::distanceToEntityComparator)
                .ifPresent(monster -> {
                    orders.add(new Move(windBlower, Priority.WIND_BLOWER + (int) (4 * HERO_MOVE / windBlower.position.distanceTo(monster.position())),
                            monster, monster.position(), "pssstt!"));

                });

        // search for previously seen monsters
        monsterCache.values()
                .stream()
                .filter(m -> m.position.distanceTo(windBlower.position) >= HERO_FOG_RADIUS)
                .min(windBlower::distanceToEntityComparator)
                .ifPresent(monster -> {
                    orders.add(new Move(windBlower, Priority.WIND_BLOWER + (int) (4 * HERO_MOVE / windBlower.position.distanceTo(monster.position())),
                            null, monster.position(), "back to track.."));
                });


        double angle = 2 * Math.PI * Math.random();
        orders.add(new Move(windBlower, Priority.WIND_BLOWER - 10, null,
                windBlower.position.translate(HERO_MOVE * cos(angle), HERO_MOVE * sin(angle))
                        .clamp(ORIGIN1, ORIGIN2),
                "*sifflote*"));
    }

    private Map<Integer, Point> attemptToKillThemAll(Round round, List<Entity> monsters, List<Entity> heroes) {
        List<Item> monsterAsItems = monsters.stream().map(Item::new).collect(toList());

        int barycentreX = 0;
        int barycentreY = 0;
        List<Point> pointOfInterests = new ArrayList<>();
        for (Entity m : monsters) {
            barycentreX += m.position.x;
            barycentreY += m.position.y;
            pointOfInterests.add(m.destination());
        }
        pointOfInterests.add(new Point(barycentreX / monsters.size(), barycentreY / monsters.size()));

        // try with all heroes
        for (Point p1 : pointOfInterests) {
            for (Point p2 : pointOfInterests) {
                Entity hero1 = heroes.get(0);
                Entity hero2 = heroes.get(1);

                Vector speed1 = Vector.of(hero1.position, p1).scale(HERO_MOVE);
                Vector speed2 = Vector.of(hero2.position, p2).scale(HERO_MOVE);

                Blackboard blackboard = new Blackboard(round.myHealth,
                        warField,
                        monsterAsItems,
                        asList(new Item(hero1, speed1), new Item(hero2, speed2)),
                        (bb, hs) -> {
                            hs.forEach(h -> {
                                bb.monsters.stream().min(h::distanceToItemComparator).ifPresent(m -> h.speed = Vector.of(h.position, m.position).scale(HERO_MOVE));
                            });
                        });
                BlackboardStatus status = blackboard.evaluate(10);
                if (status == BlackboardStatus.ALL_DEAD) {
                    return mapOf(
                            hero1.id, hero1.position.translate(speed1),
                            hero2.id, hero2.position.translate(speed2)
                    );
                }
            }
        }

        return null;
    }

    private static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        Map<K, V> hash = new HashMap<>();
        hash.put(k1, v1);
        hash.put(k2, v2);
        return hash;
    }

    private Set<Integer> entityIdsInWindRange(List<Entity> entities, Point position) {
        return entities.stream().filter(e -> e.position.distanceTo(position) <= WIND_EFFECT_RANGE).map(Entity::id).collect(toSet());
    }

    //                  _      _           _       _
    //   ___ _ __  _ __(_) ___| |__     __| | __ _| |_ __ _
    //  / _ \ '_ \| '__| |/ __| '_ \   / _` |/ _` | __/ _` |
    // |  __/ | | | |  | | (__| | | | | (_| | (_| | || (_| |
    //  \___|_| |_|_|  |_|\___|_| |_|  \__,_|\__,_|\__\__,_|
    //
    private void enrichData(Round round) {
        round.myHeroes.forEach(e -> {
            double distance = basePosition().distanceTo(e.position);
            e.affectDistanceToBase(distance);
        });

        for (MonsterTrack track : new ArrayList<>(monsterCache.values())) {
            track.tick();
            if (track.isOut()) {
                monsterCache.remove(track.id);
            }
        }

        round.monsters.stream()
                .forEach(monster -> {
                    double distance = basePosition().distanceTo(monster.position);
                    monster.affectDistanceToBase(distance);
                    if (underControlLastRound.contains(monster.id)) {
                        monster.wasUnderControlLastRound = true;
                    }
                    monsterCache.put(monster.id,
                            new MonsterTrack(round.round, monster.id, monster.position, monster.speed));
                });
    }

    private static <T> Stream<T> selectAround(Point position, Stream<T> entities, Function<T, Point> positionProvider, int radius) {
        return entities.filter(e -> positionProvider.apply(e).distanceTo(position) <= radius);
    }

    private static <T> Stream<T> selectAround(Point position, List<T> entities, Function<T, Point> positionProvider, int radius) {
        return selectAround(position, entities.stream(), positionProvider, radius);
    }

    private static <T> long countAround(Point position, List<T> entities, Function<T, Point> positionProvider, int radius) {
        return selectAround(position, entities.stream(), positionProvider, radius).count();
    }

    // -----------------------------------------------------------------------------------------------------------------
    //     ___         _
    //    /___\_ __ __| | ___ _ __ ___
    //   //  // '__/ _` |/ _ \ '__/ __|
    //  / \_//| | | (_| |  __/ |  \__ \
    //  \___/ |_|  \__,_|\___|_|  |___/
    //
    // -----------------------------------------------------------------------------------------------------------------
    interface Order {
        int heroId();

        String asString();

        int priority();

        static int priorityComparator(Order o1, Order o2) {
            return Integer.compare(o1.priority(), o2.priority());
        }
    }

    interface Priority {
        int EMERGENCY = 90000;
        int MULTIPLE_THREATS = 8000;
        int WIND_BLOWER = 6000;
        int THREAT = 5000;
        int BACK_TO_BASE = 2000;
        int IDLE = 100;
        int FARMING = 1000;
    }

    static class Move implements Order {
        Entity hero;
        int priority;
        Entity monster;
        Point target;
        String message;

        public Move(Entity hero, int priority, Entity monster, Point target, String message) {
            this.hero = hero;
            this.priority = priority;
            this.monster = monster;
            this.target = target;
            this.message = message;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int heroId() {
            return hero.id;
        }

        @Override
        public String asString() {
            return String.format("MOVE %d %d %s", target.x, target.y, message);
        }

        @Override
        public String toString() {
            return "{" + hero.id + "," + priority + '}' + asString();
        }
    }

    static class Wind implements Order {
        Entity hero;
        int priority;
        String message;
        Point target;
        Set<Integer> entityIdsAffected;

        public Wind(Entity hero, int priority, Point target, String message, Set<Integer> entityIdsAffected) {
            this.hero = hero;
            this.priority = priority;
            this.message = message;
            this.target = target;
            this.entityIdsAffected = entityIdsAffected;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int heroId() {
            return hero.id;
        }

        @Override
        public String asString() {
            return String.format("SPELL WIND %d %d %s", target.x, target.y, message);
        }

        public Set<Integer> entityIdsAffected() {
            return entityIdsAffected;
        }

        @Override
        public String toString() {
            return "{" + hero.id + "," + priority + '}' + asString();
        }

    }

    static class Control implements Order {
        Entity hero;
        int priority;
        String message;
        Entity entity;
        Point target;

        public Control(Entity hero,
                       int priority,
                       Entity entity,
                       Point target,
                       String message) {
            this.hero = hero;
            this.priority = priority;
            this.entity = entity;
            this.target = target;
            this.message = message;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int heroId() {
            return hero.id;
        }

        @Override
        public String asString() {
            return String.format("SPELL CONTROL %d %d %d %s", entity.id, target.x, target.y, message);
        }

        public boolean isSimilarTo(Order o) {
            if (o instanceof Control) {
                Control other = (Control) o;
                return other.entity.id == entity.id;
            }
            return false;
        }

        @Override
        public String toString() {
            return "{" + hero.id + "," + priority + '}' + asString();
        }

    }

    static class Shield implements Order {
        final Entity hero;
        final int priority;
        final Entity target;
        final String message;

        public Shield(Entity hero, int priority, Entity target, String message) {
            this.hero = hero;
            this.priority = priority;
            this.target = target;
            this.message = message;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int heroId() {
            return hero.id;
        }

        @Override
        public String asString() {
            return String.format("SPELL SHIELD %d %s", target.id, message);
        }

        @Override
        public String toString() {
            return "{" + hero.id + "," + priority + ",@" + target.id + '}' + asString();
        }

    }


    // -----------------------------------------------------------------------------------------------------------------
    //  __    __               ___ _      _     _
    // / / /\ \ \__ _ _ __    / __(_) ___| | __| |
    // \ \/  \/ / _` | '__|  / _\ | |/ _ \ |/ _` |
    //  \  /\  / (_| | |    / /   | |  __/ | (_| |
    //   \/  \/ \__,_|_|    \/    |_|\___|_|\__,_|
    //
    // -----------------------------------------------------------------------------------------------------------------


    public static class WarField {
        private final Point basePosition;
        private final Point opponentBasePosition;
        private final List<Point> baseSentinelPoints;
        private final Vector baseEmergencyWindPush;
        private final List<Point> redirectPoints;
        private int redirectPointIndex = 0;

        WarField(Point basePosition,
                 Point opponentBasePosition,
                 List<Point> baseSentinelPoints,
                 Vector baseEmergencyWindPush,
                 List<Point> redirectPoints) {
            this.basePosition = basePosition;
            this.opponentBasePosition = opponentBasePosition;
            this.baseSentinelPoints = baseSentinelPoints;
            this.baseEmergencyWindPush = baseEmergencyWindPush;
            this.redirectPoints = redirectPoints;
        }

        public Point nextRedirectPoint(HasPosition actualPosition) {
            return redirectPoints.stream().min(actualPosition.position()::distanceToHasPositionComparator).get();
        }

    }

    static final Point ORIGIN1 = new Point(0, 0);
    static final Point ORIGIN2 = new Point(17630, 9000);

    private static WarField computeWarField(Point basePosition) {
        Point opponentBasePosition;
        List<Point> baseSentinelPoints;
        Vector baseEmergencyWindPush;
        List<Point> redirectPoints = asList(new Point(200, 4000), new Point(4000, 200));

        int sentinelRadiusPosition = 5500;

        double baseAngleSentinel = Math.PI / 6;
        if (basePosition.closeTo(ORIGIN1, 800)) {
            opponentBasePosition = ORIGIN2;
            baseSentinelPoints = asList(
                    ORIGIN1.translate(new Vector(cos(1 * baseAngleSentinel), sin(1 * baseAngleSentinel))
                            .scale(sentinelRadiusPosition)),
                    ORIGIN1.translate(new Vector(cos(2 * baseAngleSentinel), sin(2 * baseAngleSentinel))
                            .scale(sentinelRadiusPosition)));
            baseEmergencyWindPush = new Vector(cos(Math.PI / 4), sin(Math.PI / 4)).scale(4 * HERO_MOVE);
            redirectPoints = redirectPoints.stream().map(pt -> ORIGIN2.translate(-pt.x, -pt.y)).collect(toList());
        } else {
            opponentBasePosition = ORIGIN1;
            baseSentinelPoints = asList(
                    ORIGIN2.translate(new Vector(cos(1 * baseAngleSentinel), sin(1 * baseAngleSentinel))
                            .scale(-sentinelRadiusPosition)),
                    ORIGIN2.translate(new Vector(cos(2 * baseAngleSentinel), sin(2 * baseAngleSentinel))
                            .scale(-sentinelRadiusPosition)));
            baseEmergencyWindPush = new Vector(cos(Math.PI / 4), sin(Math.PI / 4)).scale(-4 * HERO_MOVE);
        }
        return new WarField(basePosition, opponentBasePosition, baseSentinelPoints, baseEmergencyWindPush,
                redirectPoints);
    }


    // -----------------------------------------------------------------------------------------------------------------
    //                   _
    //   /\   /\___  ___| |_ ___  _ __
    //   \ \ / / _ \/ __| __/ _ \| '__|
    //    \ V /  __/ (__| || (_) | |
    //     \_/ \___|\___|\__\___/|_|
    //
    // -----------------------------------------------------------------------------------------------------------------
    static class Vector {
        final double vx;
        final double vy;
        double len = -1;

        Vector(double vx, double vy) {
            this.vx = vx;
            this.vy = vy;
        }

        public static Vector ofAngle(double angle) {
            return new Vector(cos(angle), sin(angle));
        }

        public double length() {
            if (len == -1)
                len = Math.sqrt(vx * vx + vy * vy);
            return len;
        }

        public Vector scale(int length) {
            double l = length();
            double nvx = Math.ceil(vx * length / l);
            double nvy = Math.ceil(vy * length / l);
            return new Vector(nvx, nvy);
        }

        public static Vector of(Point a, Point b) {
            return new Vector(b.x - a.x, b.y - a.y);
        }

        public Vector rotate(double angleDelta) {
            double cAng = cos(angleDelta);
            double sAng = sin(angleDelta);
            return new Vector(vx * cAng - vy * sAng, vx * sAng + vy * cAng);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    //    ___      _       _
    //   / _ \___ (_)_ __ | |_
    //  / /_)/ _ \| | '_ \| __|
    // / ___/ (_) | | | | | |_
    // \/    \___/|_|_| |_|\__|
    //
    // -----------------------------------------------------------------------------------------------------------------
    static class Point implements HasPosition {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public Point position() {
            return this;
        }

        public double distanceTo(Point p) {
            double dx = (p.x - x);
            double dy = (p.y - y);
            return Math.sqrt(dx * dx + dy * dy);
        }

        public String toString() {
            return "{" + x + ", " + y + "}";
        }

        public boolean closeTo(Point other, double delta) {
            return distanceTo(other) < delta;
        }

        public Point translate(int dx, int dy) {
            return new Point(x + dx, y + dy);
        }

        public Point translate(double dx, double dy) {
            return new Point((int) (x + dx), (int) (y + dy));
        }

        public Point translate(Vector v) {
            return new Point((int) Math.ceil(x + v.vx), (int) Math.ceil(y + v.vy));
        }

        private static int clamp(int a, int min, int max) {
            if (a < min)
                return min;
            return Math.min(a, max);
        }

        public Point clamp(Point min, Point max) {
            int nx = clamp(x, min.x, max.x);
            int ny = clamp(y, min.y, max.y);

            return new Point(nx, ny);
        }

        public int distanceToHasPositionComparator(HasPosition hp1, HasPosition hp2) {
            double d1 = this.distanceTo(hp1.position());
            double d2 = this.distanceTo(hp2.position());
            return Double.compare(d1, d2);
        }
    }

    interface HasPosition {
        Point position();
    }

    // -----------------------------------------------------------------------------------------------------------------
    //  _     _            _    _                         _
    // | |__ | | __ _  ___| | _| |__   ___   __ _ _ __ __| |
    // | '_ \| |/ _` |/ __| |/ / '_ \ / _ \ / _` | '__/ _` |
    // | |_) | | (_| | (__|   <| |_) | (_) | (_| | | | (_| |
    // |_.__/|_|\__,_|\___|_|\_\_.__/ \___/ \__,_|_|  \__,_|
    //
    // -----------------------------------------------------------------------------------------------------------------
    static class Item {
        final Entity entity;
        Vector speed;
        Point position;
        int life;
        boolean active = true;

        Item(Entity entity) {
            this(entity, entity.speed);
        }

        Item(Entity entity, Vector speed) {
            this.entity = entity;
            this.position = entity.position;
            this.life = entity.health;
            this.speed = speed;
        }

        void tick() {
            this.position = destination();
        }

        public Point destination() {
            return this.position.translate(speed);
        }

        public boolean active() {
            return active;
        }

        public void deactivate() {
            active = false;
        }

        public void takeDamage() {
            this.life -= 2;
            if (life <= 0)
                deactivate();
        }

        boolean isDead() {
            return life <= 0;
        }

        public int distanceToItemComparator(Item item1, Item item2) {
            double d1 = position.distanceTo(item1.position);
            double d2 = position.distanceTo(item2.position);
            return Double.compare(d1, d2);
        }
    }

    enum BlackboardStatus {
        LOST,
        ALL_DEAD,
        UNKNOWN,
    }

    interface Updater {
        void update(Blackboard blackboard, List<Item> heroes);
    }

    static class Blackboard {
        final int myHealth;
        final WarField warField;
        final List<Item> monsters;
        final List<Item> heroes;
        int lifeLost = 0;

        Blackboard(int myHealth, WarField warField, List<Item> monsters, List<Item> heroes, Updater updater) {
            this.myHealth = myHealth;
            this.warField = warField;
            this.monsters = monsters;
            this.heroes = heroes;
        }

        BlackboardStatus evaluate(int maxRound) {
            for (int round = 0; round < maxRound; round++) {
                tick();
                if (lifeLost > myHealth)
                    return BlackboardStatus.LOST;
                if (areMonstersDead()) {
                    return BlackboardStatus.ALL_DEAD;
                }
            }
            return BlackboardStatus.UNKNOWN;
        }

        void tick() {
            heroes.forEach(Item::tick);
            monsters.stream().filter(Item::active).forEach(m -> {
                m.tick();
                if (m.position.distanceTo(warField.basePosition) < 300) {
                    m.deactivate();
                    lifeLost++;
                } else {
                    heroes.stream()
                            .filter(h -> isInDamageRange(m.position, h.position))
                            .forEach(h -> m.takeDamage());
                }
            });
        }

        public boolean areMonstersDead() {
            return monsters.stream().allMatch(Item::isDead);
        }
    }

    private static boolean isInDamageRange(Point p1, Point p2) {
        return p1.distanceTo(p2) < Player.HERO_DAMAGE_EFFECT_RANGE;
    }

    // -----------------------------------------------------------------------------------------------------------------
    //                         _              _____                _
    //   /\/\   ___  _ __  ___| |_ ___ _ __  /__   \_ __ __ _  ___| | __
    //  /    \ / _ \| '_ \/ __| __/ _ \ '__|   / /\/ '__/ _` |/ __| |/ /
    // / /\/\ \ (_) | | | \__ \ ||  __/ |     / /  | | | (_| | (__|   <
    // \/    \/\___/|_| |_|___/\__\___|_|     \/   |_|  \__,_|\___|_|\_\
    //
    // -----------------------------------------------------------------------------------------------------------------
    static class MonsterTrack implements HasPosition {
        final int atRound;
        final int id;
        Point position;
        final Vector speed;

        MonsterTrack(int atRound, int id, Point position, Vector speed) {
            this.atRound = atRound;
            this.id = id;
            this.position = position;
            this.speed = speed;
        }

        @Override
        public Point position() {
            return position;
        }

        void tick() {
            this.position = destination();
        }

        boolean isOut() {
            return position.x < ORIGIN1.x
                    || position.x > ORIGIN2.x
                    || position.y < ORIGIN1.y
                    || position.y > ORIGIN2.y;
        }

        public Point destination() {
            return this.position.translate(speed);
        }
    }


    // -----------------------------------------------------------------------------------------------------------------
    //    __      _   _ _
    //   /__\ __ | |_(_) |_ _   _
    //  /_\| '_ \| __| | __| | | |
    // //__| | | | |_| | |_| |_| |
    // \__/|_| |_|\__|_|\__|\__, |
    //                      |___/
    // -----------------------------------------------------------------------------------------------------------------
    static class Entity implements HasPosition {
        final int id;
        final int type;
        final Point position;
        final int shieldLife;
        final int isControlled;
        final int health;
        final Vector speed;
        final int nearBase;
        final int threatFor;
        //
        boolean wasUnderControlLastRound = false;
        double distanceToBase = Double.MAX_VALUE;
        int nbRoundRemaining = Integer.MAX_VALUE;
        int nbHeroRequired = Integer.MAX_VALUE;

        Entity(int id, int type, Point position, int shieldLife, int isControlled, int health, Vector speed,
               int nearBase, int threatFor) {
            this.id = id;
            this.type = type;
            this.position = position;
            this.shieldLife = shieldLife;
            this.isControlled = isControlled;
            this.health = health;
            this.speed = speed;
            this.nearBase = nearBase;
            this.threatFor = threatFor;
        }

        @Override
        public Point position() {
            return position;
        }

        public int id() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Entity entity = (Entity) o;
            return id == entity.id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        public boolean notUnderControlLastRound() {
            return !wasUnderControlLastRound;
        }

        public int health() {
            return health;
        }

        public Point destination() {
            return position.translate(speed);
        }

        public Entity affectDistanceToBase(double distance) {
            this.distanceToBase = distance;
            this.nbRoundRemaining = (int) (Math.floor(distance) / rawSpeed());
            // Assume the heroes start doing damage right now...
            // nbHero * HERO_DAMAGE * nbRound >= monster.health
            this.nbHeroRequired = (int) Math.ceil(health / (1.0 * HERO_DAMAGE * nbRoundRemaining));
            return this;
        }

        public double distanceToBase() {
            return distanceToBase;
        }

        public double rawSpeed() {
            return speed.length();
        }

        public int nbRoundRemaining() {
            return nbRoundRemaining;
        }

        public boolean isNotControlled() {
            return isControlled != 1;
        }

        public boolean isControlled() {
            return isControlled == 1;
        }

        public boolean hasShield() {
            return shieldLife > 0;
        }

        public boolean isThreat() {
            return threatFor == 1;
        }

        public boolean isOpponentThreat() {
            return threatFor == 2;
        }

        public boolean noThreat() {
            return threatFor == 0;
        }

        public int distanceToEntityComparator(HasPosition entity1, HasPosition entity2) {
            return distanceToPointComparator(entity1.position(), entity2.position());
        }

        public int distanceToPointComparator(Point entity1, Point entity2) {
            double d1 = position.distanceTo(position);
            double d2 = position.distanceTo(position);
            return Double.compare(d1, d2);
        }

        public static int distanceToBaseComparator(Entity entity1, Entity entity2) {
            return Double.compare(entity1.distanceToBase, entity2.distanceToBase);
        }

        public static Point moveHeroInDirectionOf(Entity hero, Point target) {
            Vector dir = Vector.of(hero.position, target).scale(HERO_MOVE);
            return hero.position.translate(dir);
        }

        public static int healthComparator(Entity e1, Entity e2) {
            return Integer.compare(e1.health, e2.health);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    //     __                       _
    //    /__\ ___  _   _ _ __   __| |
    //   / \/// _ \| | | | '_ \ / _` |
    //  / _  \ (_) | |_| | | | | (_| |
    //  \/ \_/\___/ \__,_|_| |_|\__,_|
    //
    // -----------------------------------------------------------------------------------------------------------------
    static class Round {
        int round;
        int myHealth;
        int myMana;
        int oppHealth;
        int oppMana;
        List<Entity> myHeroes;
        List<Entity> oppHeroes;
        List<Entity> monsters;

        Round(int round, int myHealth, int myMana, int oppHealth, int oppMana, List<Entity> myHeroes,
              List<Entity> oppHeroes, List<Entity> monsters) {
            this.round = round;
            this.myHealth = myHealth;
            this.myMana = myMana;
            this.oppHealth = oppHealth;
            this.oppMana = oppMana;
            this.myHeroes = myHeroes;
            this.oppHeroes = oppHeroes;
            this.monsters = monsters;
        }
    }

}