package com.github.mikekwright.sip.softphone.terminal;

import com.github.mikekwright.sip.softphone.Softphone;
import com.github.mikekwright.sip.softphone.SoftphoneConfig;
import com.github.mikekwright.sip.softphone.sip.transport.TransportType;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Command line entry point of the terminal softphone (audio only).
 *
 * <pre>
 * terminal-softphone --user alice --port 5070                     # direct calls, no registrar
 * terminal-softphone --user alice --domain pbx.local --password s3cret --register
 * terminal-softphone --user bot --port 5080 --auto-answer --audio echo     # echo test endpoint
 * terminal-softphone --user t --port 0 --batch --exec "call sip:bob@127.0.0.1:5080; await connected 5; \
 *     dtmf 1234; wait 1; hangup"                                   # scripted test, exit code reflects result
 * </pre>
 */
public final class TerminalSoftphone {

    private TerminalSoftphone() {
    }

    /** Parsed command line options. */
    record Options(SoftphoneConfig config, boolean register, String autoAnswer, String audio, String record,
                   String trace, List<String> commands, boolean batch) {
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(usage());
            System.exit(2);
            return;
        }
        if (options == null) {
            System.out.println(usage());
            return;
        }
        System.exit(run(options));
    }

    static int run(Options options) throws Exception {
        Softphone phone = Softphone.create(options.config());
        phone.start().get();
        Terminal terminal = options.batch() ? null : TerminalBuilder.builder().system(true).build();
        LineReader reader = terminal == null ? null : LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("softphone")
                .completer(new StringsCompleter(CommandShell.COMMANDS))
                .build();
        CommandShell shell = new CommandShell(phone, line -> {
            if (reader != null && reader.isReading()) {
                reader.printAbove(line);
            } else {
                System.out.println(line);
            }
        });
        shell.configure(options.audio(), options.record(), options.autoAnswer(), options.trace());
        shell.print("SIP softphone ready: " + phone.localUri() + "  (type 'help')");
        int exit = 0;
        try {
            if (options.register()) {
                exit |= shell.execute("register") ? 0 : 1;
            }
            for (String cmd : options.commands()) {
                if (!shell.execute(cmd)) {
                    exit = 1;
                    if (options.batch()) {
                        break;
                    }
                }
                if (shell.quitRequested()) {
                    break;
                }
            }
            if (!options.batch() && !shell.quitRequested()) {
                interactive(shell, reader);
            }
        } finally {
            shell.close();
            phone.stop().get();
            if (terminal != null) {
                terminal.close();
            }
        }
        return exit;
    }

    private static void interactive(CommandShell shell, LineReader reader) throws IOException {
        if (reader == null) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while (!shell.quitRequested() && (line = in.readLine()) != null) {
                shell.execute(line);
            }
            return;
        }
        while (!shell.quitRequested()) {
            String line;
            try {
                line = reader.readLine(shell.prompt());
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                return;
            }
            shell.execute(line);
        }
    }

    static Options parse(String[] args) throws IOException {
        SoftphoneConfig.Builder b = SoftphoneConfig.builder()
                .user(System.getProperty("user.name", "softphone").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", ""))
                .domain("127.0.0.1")
                .sipPort(5060)
                .videoCodecs(List.of());
        boolean register = false;
        String autoAnswer = null;
        String audio = null;
        String record = null;
        String trace = null;
        boolean batch = false;
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String value = null;
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 0) {
                value = a.substring(eq + 1);
                a = a.substring(0, eq);
            }
            switch (a) {
                case "-h", "--help" -> {
                    return null;
                }
                case "--user", "-u" -> b.user(value != null ? value : args[++i]);
                case "--domain", "-d" -> b.domain(value != null ? value : args[++i]);
                case "--display-name" -> b.displayName(value != null ? value : args[++i]);
                case "--password", "-p" -> b.password(value != null ? value : args[++i]);
                case "--auth-user" -> b.authUser(value != null ? value : args[++i]);
                case "--registrar" -> b.registrar(value != null ? value : args[++i]);
                case "--proxy" -> b.outboundProxy(value != null ? value : args[++i]);
                case "--transport", "-t" -> b.transport(TransportType.valueOf(
                        (value != null ? value : args[++i]).toUpperCase(Locale.ROOT)));
                case "--port" -> b.sipPort(Integer.parseInt(value != null ? value : args[++i]));
                case "--bind" -> b.bindHost(value != null ? value : args[++i]);
                case "--host" -> b.advertisedHost(value != null ? value : args[++i]);
                case "--rtp-ports" -> {
                    String[] range = (value != null ? value : args[++i]).split("-");
                    b.rtpPorts(Integer.parseInt(range[0]), Integer.parseInt(range[1]));
                }
                case "--codecs" -> b.audioCodecs(Arrays.asList((value != null ? value : args[++i]).split(",")));
                case "--register" -> register = true;
                case "--auto-answer" -> autoAnswer = value != null ? value : "0";
                case "--audio" -> audio = value != null ? value : args[++i];
                case "--record" -> record = value != null ? value : args[++i];
                case "--trace" -> trace = value != null ? value : "short";
                case "--exec", "-e" -> commands.addAll(splitCommands(value != null ? value : args[++i]));
                case "--script" -> commands.addAll(Files.readAllLines(Path.of(value != null ? value : args[++i])));
                case "--batch" -> batch = true;
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        SoftphoneConfig config = b.build();
        if (register && config.registrar() == null) {
            b.registrar("sip:" + config.domain());
            config = b.build();
        }
        return new Options(config, register, autoAnswer, audio, record, trace, commands, batch);
    }

    static List<String> splitCommands(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("[;\\n]")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    static String usage() {
        return """
                Usage: terminal-softphone [options]

                Identity / account
                  --user, -u NAME          user part of our SIP address (default: OS user)
                  --domain, -d HOST        domain of our address-of-record (default 127.0.0.1)
                  --display-name NAME      display name
                  --password, -p SECRET    password for digest authentication
                  --auth-user NAME         authentication user name (default: --user)
                  --registrar URI          registrar (default sip:<domain> when --register is given)
                  --register               register on start-up
                  --proxy URI              outbound proxy

                Network
                  --transport, -t udp|tcp  SIP transport (default udp)
                  --port N                 local SIP port, 0 = any (default 5060)
                  --bind ADDR              local bind address (default 0.0.0.0)
                  --host ADDR              address advertised in Via/Contact/SDP
                  --rtp-ports MIN-MAX      RTP port range (default 20000-29998)
                  --codecs PCMU,PCMA       audio codecs in preference order

                Media
                  --audio SPEC             audio source: device | silence | tone[:HZ] | file:PATH | echo
                                           (default: device when available, else silence)
                  --record PATH            record received audio of each call to PATH (WAV; %n = call number)

                Automation
                  --auto-answer[=SECONDS]  answer incoming calls automatically (after a delay)
                  --trace[=short|full]     print SIP messages
                  --exec, -e "CMD; CMD"    run commands at start-up (see 'help' inside the shell)
                  --script FILE            run commands from a file
                  --batch                  no interactive shell: run the commands and exit (exit code 1 if a
                                           command or an 'await' failed)
                """;
    }
}
