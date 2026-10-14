package io.github.kormcrete.swingui.cli;

import io.github.kormcrete.swingui.client.AgentConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** {@code java -jar swing-ui-agent.jar [--host H] [--port N] [--out FILE] [--lease T] <tool> ['<json args>']}: one call, then exit. */
public final class UiCli {

    static final int OK = 0, TOOL_ERROR = 1, USAGE = 2, NOT_RUNNING = 3, FLOW_FAILED = 4;

    static final String USAGE_TEXT = String.join("\n",
            "usage: java -jar swing-ui-agent.jar [--host 127.0.0.1] [--port 8765] [--out FILE] <tool> ['<json arguments>']",
            "       java -jar swing-ui-agent.jar [--port N] tools [<tool>]   list the tools, or one tool's full schema",
            "  --lease T   pass a held lease's token (session op 'lease' returns one)",
            "  --out FILE   for a tool that returns an image (screenshot): write the PNG to FILE",
            "exit: 0 ok, 1 tool or agent error (stderr), 2 usage, 3 no agent reachable, 4 run_flow: the flow failed (report on stderr)");

    private UiCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String host = "127.0.0.1";
        int port = 8765;
        String outFile = null;
        String leaseToken = null;
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (("--host".equals(a) || "--port".equals(a) || "--out".equals(a) || "--lease".equals(a)) && i + 1 >= args.length) {
                err.println(a + " needs a value\n" + USAGE_TEXT);
                return USAGE;
            }
            if ("--host".equals(a)) host = args[++i];
            else if ("--port".equals(a)) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    err.println("--port must be a number\n" + USAGE_TEXT);
                    return USAGE;
                }
            } else if ("--out".equals(a)) outFile = args[++i];
            else if ("--lease".equals(a)) leaseToken = args[++i];
            else if ("--help".equals(a) || "-h".equals(a)) {
                err.println(USAGE_TEXT);
                return USAGE;
            } else rest.add(a);
        }
        if (rest.isEmpty()) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        ObjectMapper mapper = new ObjectMapper();
        String tool = rest.get(0);
        try {
            AgentConnection agent = new AgentConnection(host, port, mapper);
            if ("tools".equals(tool)) return listTools(agent, rest.size() > 1 ? rest.get(1) : null, out, err, mapper);
            JsonNode arguments;
            try {
                arguments = mapper.readTree(rest.size() > 1 ? rest.get(1) : "{}");
            } catch (IOException e) {
                err.println("arguments are not JSON: " + e.getMessage());
                return USAGE;
            }
            if (leaseToken != null && arguments.isObject()) ((ObjectNode) arguments).put("_lease", leaseToken);
            JsonNode result = agent.callTool(tool, arguments);
            if (result.path("isError").asBoolean(false)) {
                err.println(firstText(result));
                return TOOL_ERROR;
            }
            int code = print(result, outFile == null ? null : Paths.get(outFile), out, err);
            if (code == OK && "run_flow".equals(tool)) {
                try {
                    JsonNode answer = mapper.readTree(firstText(result));
                    if (!answer.path("passed").asBoolean(true)) {
                        err.println(answer.path("report").asText(""));
                        return FLOW_FAILED;
                    }
                } catch (IOException e) {
                    // not JSON: nothing to judge, the text was printed
                }
            }
            return code;
        } catch (ConnectException e) {
            err.println(new io.github.kormcrete.swingui.client.AgentUnreachable(host + ":" + port, e).getMessage());
            return NOT_RUNNING;
        } catch (SocketTimeoutException e) {
            err.println("swing-ui-agent on " + host + ":" + port + " did not answer: " + e);
            return NOT_RUNNING;
        } catch (AgentConnection.RpcError e) {
            err.println(e.getMessage());
            return TOOL_ERROR;
        } catch (IOException e) {
            // it is running, so 3 ("start the app") would send the caller the wrong way
            err.println("swing-ui-agent on " + host + ":" + port + " failed: "
                    + (e instanceof AgentConnection.HttpError ? e.getMessage() : e.toString()));
            return TOOL_ERROR;
        }
    }

    private static int listTools(AgentConnection agent, String only, PrintStream out, PrintStream err, ObjectMapper m)
            throws IOException {
        JsonNode tools = agent.request("tools/list", m.createObjectNode()).path("tools");
        for (JsonNode t : tools) {
            if (only == null) {
                String description = t.path("description").asText("");
                int cut = description.indexOf(". ");
                out.println(t.path("name").asText() + "  —  " + (cut > 0 ? description.substring(0, cut + 1) : description));
            } else if (only.equals(t.path("name").asText())) {
                out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(t));
                return OK;
            }
        }
        if (only != null) {
            err.println("no tool '" + only + "'");
            return USAGE;
        }
        return OK;
    }

    private static String firstText(JsonNode result) {
        for (JsonNode block : result.path("content")) {
            if (block.hasNonNull("text")) return block.path("text").asText();
        }
        return "";
    }

    /** Walks content[]: text blocks print; an image block goes to --out (its path is printed) or is described. */
    private static int print(JsonNode result, Path outFile, PrintStream out, PrintStream err) {
        JsonNode content = result.path("content");
        boolean written = false;
        for (JsonNode block : content) {
            String type = block.path("type").asText("text");
            if ("image".equals(type)) {
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(block.path("data").asText(""));
                } catch (IllegalArgumentException e) {
                    err.println("--out: the image data is not base64: " + e.getMessage());
                    return TOOL_ERROR;
                }
                if (outFile == null) {
                    out.println("[image " + block.path("mimeType").asText("?") + ", " + bytes.length + " bytes; use --out FILE]");
                } else if (written) {
                    out.println("[further image skipped: --out holds one file]");
                } else {
                    try {
                        Path parent = outFile.toAbsolutePath().getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.write(outFile, bytes);
                    } catch (IOException e) {
                        err.println("--out " + outFile + ": cannot write the image: " + e);
                        return TOOL_ERROR;
                    }
                    written = true;
                    out.println(outFile.toAbsolutePath());
                }
            } else if (block.hasNonNull("text")) {
                out.println(block.path("text").asText());
            }
        }
        if (content.size() == 0) out.println();
        return OK;
    }
}
