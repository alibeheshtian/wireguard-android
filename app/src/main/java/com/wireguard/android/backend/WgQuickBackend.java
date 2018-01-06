package com.wireguard.android.backend;

import android.content.Context;
import android.util.Log;

import com.wireguard.android.model.Tunnel;
import com.wireguard.android.model.Tunnel.State;
import com.wireguard.android.model.Tunnel.Statistics;
import com.wireguard.android.util.AsyncWorker;
import com.wireguard.android.util.RootShell;
import com.wireguard.config.Config;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import java9.util.concurrent.CompletableFuture;
import java9.util.concurrent.CompletionStage;
import java9.util.stream.Collectors;
import java9.util.stream.Stream;

/**
 * Created by samuel on 12/19/17.
 */

public final class WgQuickBackend implements Backend {
    private static final String TAG = WgQuickBackend.class.getSimpleName();

    private final AsyncWorker asyncWorker;
    private final Context context;
    private final RootShell rootShell;

    public WgQuickBackend(final AsyncWorker asyncWorker, final Context context,
                          final RootShell rootShell) {
        this.asyncWorker = asyncWorker;
        this.context = context;
        this.rootShell = rootShell;
    }

    private static State resolveState(final State currentState, State requestedState) {
        if (requestedState == State.UNKNOWN)
            throw new IllegalArgumentException("Requested unknown state");
        if (requestedState == State.TOGGLE)
            requestedState = currentState == State.UP ? State.DOWN : State.UP;
        return requestedState;
    }

    @Override
    public CompletionStage<Config> applyConfig(final Tunnel tunnel, final Config config) {
        if (tunnel.getState() == State.UP)
            return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
        return CompletableFuture.completedFuture(config);
    }

    @Override
    public CompletionStage<Set<String>> enumerate() {
        return asyncWorker.supplyAsync(() -> {
            final List<String> output = new LinkedList<>();
            // Don't throw an exception here or nothing will show up in the UI.
            if (rootShell.run(output, "wg show interfaces") != 0 || output.isEmpty())
                return Collections.emptySet();
            // wg puts all interface names on the same line. Split them into separate elements.
            return Stream.of(output.get(0).split(" "))
                    .collect(Collectors.toUnmodifiableSet());
        });
    }

    @Override
    public CompletionStage<State> getState(final Tunnel tunnel) {
        Log.v(TAG, "Requested state for tunnel " + tunnel.getName());
        return enumerate().thenApply(set -> set.contains(tunnel.getName()) ? State.UP : State.DOWN);
    }

    @Override
    public CompletionStage<Statistics> getStatistics(final Tunnel tunnel) {
        return CompletableFuture.completedFuture(new Statistics());
    }

    @Override
    public CompletionStage<State> setState(final Tunnel tunnel, final State state) {
        Log.v(TAG, "Requested state change to " + state + " for tunnel " + tunnel.getName());
        return tunnel.getStateAsync().thenCompose(currentState -> asyncWorker.supplyAsync(() -> {
            final String stateName = resolveState(currentState, state).name().toLowerCase();
            final File file = new File(context.getFilesDir(), tunnel.getName() + ".conf");
            final String path = file.getAbsolutePath();
            // FIXME: Assumes file layout from FileConfigStore. Use a temporary file.
            if (rootShell.run(null, String.format("wg-quick %s '%s'", stateName, path)) != 0)
                throw new IOException("wg-quick failed");
            return tunnel;
        })).thenCompose(this::getState);
    }
}
