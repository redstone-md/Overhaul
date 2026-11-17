package dev.overhaul.minecraft.blaze.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The benchmark: a run in its own world times every preset and recommends the highest one this machine holds. The last
 * run stays on the page as a chart.
 */
final class BenchmarkPage extends SettingsPage {
    private final GameOptions game;
    private final Function<Consumer<BenchmarkResult>, String> starter;
    private BenchmarkResult result = BenchmarkResult.load();
    private String problem;

    /** @param starter starts a run that reports to the given consumer; returns why it cannot, or null */
    BenchmarkPage(GameOptions game, Runnable refresh, Function<Consumer<BenchmarkResult>, String> starter) {
        super("benchmark", SettingsIcon.TIMER, refresh);
        this.game = game;
        this.starter = starter;
    }

    void start() {
        problem = starter.apply(this::finished);
        refresh.run();
    }

    /** The last run's result, or null when none ran. */
    BenchmarkResult result() {
        return result;
    }

    private void finished(BenchmarkResult finished) {
        result = finished;
        refresh.run();
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.run")));
        rows.add(new SettingsRow.Action(text("run.name"), text("run.desc"), SettingsIcon.PLAY, () -> text("seconds", BenchmarkRun.seconds()), this::start)
                .enabledWhen(() -> game.minecraft.level == null).note(() -> problem != null ? problem : game.minecraft.level == null ? null : text("leave")));
        rows.add(new SettingsRow.Action(text("use.name"), text("use.desc"), SettingsIcon.CHECK,
                () -> result == null ? text("none") : result.recommended().label(), () -> result.recommended().apply(game.options, game, true))
                .enabledWhen(() -> result != null && !result.recommended().matches(game.options, game)));
        if (result != null) {
            rows.add(new SettingsRow.Header(text("section.last")));
            rows.add(new BenchmarkChartRow(this::result));
        }
        return rows;
    }

    @Override
    String readout() {
        return result == null ? text("none") : result.recommended().label();
    }

    @Override
    String status() {
        return problem == null ? "" : problem;
    }

    @Override
    boolean statusIsError() {
        return problem != null;
    }
}
