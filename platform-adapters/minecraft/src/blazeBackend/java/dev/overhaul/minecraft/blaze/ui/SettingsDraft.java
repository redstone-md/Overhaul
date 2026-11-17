package dev.overhaul.minecraft.blaze.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the settings screen changed and has not applied. Rows read and write through it, so the game keeps its
 * settings until Apply commits every staged value at once; leaving the screen or Reset drops them.
 */
final class SettingsDraft {
    /** A setting a row shows and changes. */
    interface Setting<T> {
        T get();

        void set(T value);

        boolean changed();
    }

    private record Staged(Object value, Consumer<Object> commit) {
    }

    private final Map<Object, Staged> staged = new LinkedHashMap<>();
    private final List<Runnable> applied = new ArrayList<>();

    /**
     * A setting seen through the draft.
     *
     * @param key    what tells this setting from every other; two values with equal keys share one staged value
     * @param live   what the game uses now
     * @param commit writes a value to the game; runs on Apply
     */
    <T> Value<T> value(Object key, Supplier<T> live, Consumer<T> commit) {
        return new Value<>(key, live, commit);
    }

    /** Runs after every Apply, once all staged values are committed; for saving files and reloading what changed. */
    void onApplied(Runnable task) {
        applied.add(task);
    }

    int count() {
        return staged.size();
    }

    void apply() {
        List<Staged> values = new ArrayList<>(staged.values());
        staged.clear();
        for (Staged value : values) value.commit().accept(value.value());
        applied.forEach(Runnable::run);
    }

    void reset() {
        staged.clear();
    }

    final class Value<T> implements Setting<T> {
        private final Object key;
        private final Supplier<T> live;
        private final Consumer<T> commit;

        private Value(Object key, Supplier<T> live, Consumer<T> commit) {
            this.key = key;
            this.live = live;
            this.commit = commit;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get() {
            Staged value = staged.get(key);
            return value == null ? live.get() : (T) value.value();
        }

        /** Staging the value the game already uses clears the change. */
        @Override
        @SuppressWarnings("unchecked")
        public void set(T value) {
            if (Objects.equals(value, live.get())) staged.remove(key);
            else staged.put(key, new Staged(value, object -> commit.accept((T) object)));
        }

        @Override
        public boolean changed() {
            return staged.containsKey(key);
        }

        /** The same setting in another unit, such as a fraction shown as a percentage. */
        <R> Setting<R> as(Function<T, R> shown, Function<R, T> stored) {
            Value<T> source = this;
            return new Setting<>() {
                @Override
                public R get() {
                    return shown.apply(source.get());
                }

                @Override
                public void set(R value) {
                    source.set(stored.apply(value));
                }

                @Override
                public boolean changed() {
                    return source.changed();
                }
            };
        }
    }
}
