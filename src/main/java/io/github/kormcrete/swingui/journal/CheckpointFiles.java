package io.github.kormcrete.swingui.journal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kormcrete.swingui.driver.CheckpointShot;
import io.github.kormcrete.swingui.driver.model.Locator;
import io.github.kormcrete.swingui.flow.FlowJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A checkpoint's two files in its test's checkpoint folder: {@code <name>.png} as the agent painted it (never scaled)
 * and the sidecar {@code <name>.json}: {@code format}, {@code name}, {@code test}, the {@code target} in words and as
 * a {@code locator}, the picture's {@code width} and {@code height}, and one entry per mask with its rectangle in the
 * picture ({@code x}, {@code y}, {@code w}, {@code h}), its {@code target}, {@code locator} and whether it was
 * {@code clipped}. Targets are masked as journal lines are ({@link TestJournal#secret}).
 */
public final class CheckpointFiles {

    private static final ObjectMapper JSON = FlowJson.registerDriverTypes(new ObjectMapper())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CheckpointFiles() {}

    /** A checkpoint file that could not be written; {@link #where} names it (or the folder it needed). */
    public static final class NotWritten extends IOException {
        public final String where;

        NotWritten(String where, Throwable cause) {
            super(where + ": " + cause.getMessage(), cause);
            this.where = where;
        }
    }

    /**
     * Writes the picture and sidecar of checkpoint {@code name} ({@link TestJournal#claimCheckpoint claimed} already)
     * of {@code target} with {@code masks}, in the order the agent measured them; returns their paths relative to the
     * run folder, picture first.
     */
    public static String[] write(TestJournal j, String name, Locator target, List<Locator> masks, CheckpointShot shot)
            throws NotWritten {
        Path dir;
        try {
            dir = j.checkpointDir();
        } catch (UncheckedIOException e) {
            throw new NotWritten(name + ".png", e);
        }
        Path png = dir.resolve(name + ".png");
        Path json = dir.resolve(name + ".json");
        try {
            Files.write(png, shot.png);
        } catch (IOException e) {
            throw new NotWritten(png.toAbsolutePath().toString(), e);
        }
        try {
            Files.write(json, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(sidecar(j, name, target, masks, shot)));
        } catch (IOException e) {
            throw new NotWritten(json.toAbsolutePath().toString(), e);
        }
        return new String[] {j.relative(png), j.relative(json)};
    }

    static ObjectNode sidecar(TestJournal j, String name, Locator target, List<Locator> masks, CheckpointShot shot) {
        ObjectNode side = NODES.objectNode();
        side.put("format", 1);
        side.put("name", name);
        side.put("test", j.test());
        putTarget(j, side, target);
        side.put("width", shot.width);
        side.put("height", shot.height);
        ArrayNode list = side.putArray("masks");
        for (int i = 0; i < shot.masks.size(); i++) {
            CheckpointShot.Mask m = shot.masks.get(i);
            ObjectNode o = list.addObject();
            o.put("x", m.x).put("y", m.y).put("w", m.w).put("h", m.h);
            putTarget(j, o, masks != null && i < masks.size() ? masks.get(i) : null);
            o.put("clipped", m.clipped);
        }
        return side;
    }

    private static void putTarget(TestJournal j, ObjectNode o, Locator target) {
        if (target == null) return;
        o.put("target", j.mask(target.toString()));
        o.set("locator", j.mask(JSON.valueToTree(target)));
    }
}
