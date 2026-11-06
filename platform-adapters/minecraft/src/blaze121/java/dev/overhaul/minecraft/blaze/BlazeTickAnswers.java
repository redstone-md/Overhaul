package dev.overhaul.minecraft.blaze;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Remembers a mod's per-frame yes-or-no question about an entity for the rest of the game tick, as what it asks
 * about (equipment, curios) only changes between ticks. The question is told apart by its subject and flag.
 * Render-thread only.
 */
public final class BlazeTickAnswers {
    private record Answer(Object subject, boolean flag, boolean value) {}
    private final Map<Entity, Answer> answers = new IdentityHashMap<>();
    private Level level;
    private long tick;

    /** The answer given earlier this tick to the same question about {@code entity}, or null. */
    public Boolean known(Entity entity, Object subject, boolean flag) {
        Level now = entity.level();
        long time = now.getGameTime();
        if (now != level || time != tick) {
            answers.clear();
            level = now;
            tick = time;
            return null;
        }
        Answer answer = answers.get(entity);
        return answer != null && answer.subject == subject && answer.flag == flag ? answer.value : null;
    }

    public void remember(Entity entity, Object subject, boolean flag, boolean value) {
        if (entity.level() == level && entity.level().getGameTime() == tick) answers.put(entity, new Answer(subject, flag, value));
    }
}
