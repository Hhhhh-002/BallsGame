package com.h.sceneserver.config;
import org.springframework.core.env.Environment;

/** 创建比赛时从已加载的 Nacos 全局配置取一次快照；热更新不改变正在进行的比赛规则。 */
public record BattleRules(int maxPlayers, int maxBalls, int maxFoods, int maxBallsPerPlayer,
        int tickRate, int broadcastRate, int maxCatchUp, float width, float height,
        float speed, int foodIntervalTicks, int matchDurationSeconds, int rankingIntervalMs, int maxParticipants,
        int initialFoods, int minPlayersToStart, int foodRefreshMaxCount) {
    public static BattleRules load(Environment env) {
        int players = env.getProperty("battle.max-players", Integer.class, 32);
        int balls = env.getProperty("battle.max-balls", Integer.class, 256);
        int foods = env.getProperty("battle.max-foods", Integer.class, 50);
        int initialFoods = env.getProperty("battle.initial-foods", Integer.class, Math.min(50, foods));
        int minPlayers = env.getProperty("battle.min-players-to-start", Integer.class, 2);
        int perPlayer = env.getProperty("battle.max-balls-per-player", Integer.class, 8);
        int tick = env.getProperty("battle.tick-rate", Integer.class, 25);
        int broadcast = env.getProperty("battle.broadcast-rate", Integer.class, 5);
        int catchUp = env.getProperty("battle.max-catch-up", Integer.class, 5);
        float width = env.getProperty("battle.width", Float.class, 100f);
        float height = env.getProperty("battle.height", Float.class, 100f);
        float speed = env.getProperty("battle.move-speed", Float.class, 10f);
        int foodInterval = env.getProperty("battle.food-interval-ms", Integer.class, 10000);
        int refreshMax = env.getProperty("battle.food-refresh-max-count", Integer.class, 5);
        int duration = env.getProperty("battle.match-duration-seconds", Integer.class, 600);
        int rankingInterval = env.getProperty("battle.ranking-interval-ms", Integer.class, 1000);
        int participants = env.getProperty("battle.max-ranking-participants", Integer.class, 256);
        // 数量上限也控制初始快照尺寸，内部链路支持 256 KiB；客户端同样应支持该上限。
        if (players < 2 || players > 64 || balls < players || balls > 256 || foods < 0 || foods > 512
                || initialFoods < 0 || initialFoods > foods || minPlayers < 2 || minPlayers > players
                || perPlayer < 1 || perPlayer > 16 || tick < 1 || tick > 100
                || broadcast < 1 || broadcast > tick || tick % broadcast != 0 || catchUp < 1 || catchUp > 10
                || !Float.isFinite(width) || !Float.isFinite(height) || width < 20 || height < 20 || width > 1000 || height > 1000
                || !Float.isFinite(speed) || speed <= 0 || speed > 100 || foodInterval < 40 || foodInterval > 3600000
                || refreshMax < 1 || refreshMax > 512
                || duration < 10 || duration > 86400 || rankingInterval < 200 || rankingInterval > 10000
                || participants < players || participants > 256) {
            throw new IllegalArgumentException("battle 配置不合法，请检查 Nacos 全局配置");
        }
        return new BattleRules(players, balls, foods, perPlayer, tick, broadcast, catchUp,
                width, height, speed, (int)Math.max(1, ((long)foodInterval * tick + 999) / 1000), duration, rankingInterval, participants,
                initialFoods, minPlayers, refreshMax);
    }
}
