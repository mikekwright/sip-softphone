package com.github.mikekwright.sip.softphone.terminal;

import com.github.mikekwright.sip.softphone.Softphone;
import com.github.mikekwright.sip.softphone.SoftphoneListener;
import com.github.mikekwright.sip.softphone.call.Call;
import com.github.mikekwright.sip.softphone.call.CallListener;
import com.github.mikekwright.sip.softphone.call.CallOptions;
import com.github.mikekwright.sip.softphone.call.CallState;
import com.github.mikekwright.sip.softphone.media.audio.AudioSink;
import com.github.mikekwright.sip.softphone.media.audio.AudioSource;
import com.github.mikekwright.sip.softphone.media.audio.NullSink;
import com.github.mikekwright.sip.softphone.media.audio.SilenceSource;
import com.github.mikekwright.sip.softphone.media.audio.ToneSource;
import com.github.mikekwright.sip.softphone.media.audio.WavFileSink;
import com.github.mikekwright.sip.softphone.media.audio.WavFileSource;
import com.github.mikekwright.sip.softphone.sip.flow.RegistrationState;
import com.github.mikekwright.sip.softphone.sip.message.NameAddress;
import com.github.mikekwright.sip.softphone.sip.message.SipMessage;
import com.github.mikekwright.sip.softphone.sip.message.SipResponse;
import com.github.mikekwright.sip.softphone.sip.session.TerminationReason;
import com.github.mikekwright.sip.softphone.sip.transport.InboundMessage;
import com.github.mikekwright.sip.softphone.sip.transport.SipMessageInterceptor;
import com.github.mikekwright.sip.softphone.sip.transport.TransportAddress;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The command interpreter of the terminal softphone. Each command maps onto one call of the library API, which
 * makes this class a compact usage example as well as a scriptable test driver.
 */
final class CommandShell implements AutoCloseable {

    static final List<String> COMMANDS = List.of("help", "status", "register", "unregister", "call", "answer",
            "reject", "hangup", "hold", "resume", "dtmf", "info-dtmf", "msg", "say", "options", "calls", "select",
            "audio", "record", "trace", "wait", "await", "expect-dtmf", "echo", "quit", "exit");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Softphone phone;
    private final Consumer<String> out;
    private final BlockingQueue<Character> receivedDtmf = new LinkedBlockingQueue<>();
    private volatile Call current;
    private volatile Supplier<AudioSource> sourceFactory = SilenceSource::new;
    private volatile String recordPattern;
    private volatile Double autoAnswerDelay;
    private volatile String traceMode = "off";
    private volatile boolean quit;

    CommandShell(Softphone phone, Consumer<String> out) {
        this.phone = phone;
        this.out = out;
        phone.addListener(new SoftphoneListener() {
            @Override
            public void onIncomingCall(Call call) {
                attach(call);
                current = call;
                print("Incoming call #" + call.number() + " from " + call.remoteParty()
                        + "  ->  'answer' or 'reject'");
                Double delay = autoAnswerDelay;
                if (delay != null) {
                    CompletableFuture.delayedExecutor((long) (delay * 1000), TimeUnit.MILLISECONDS)
                            .execute(() -> answer(call));
                }
            }

            @Override
            public void onRegistrationStateChanged(RegistrationState state, int statusCode) {
                print("Registration: " + state + (statusCode > 0 ? " (" + statusCode + ")" : ""));
            }

            @Override
            public void onMessage(NameAddress from, String contentType, String body) {
                print("Message from " + from + ": " + body);
            }
        });
        phone.addInterceptor(new SipMessageInterceptor() {
            @Override
            public SipMessage onReceive(InboundMessage inbound) {
                trace("<<", inbound.source().toString(), inbound.message());
                return inbound.message();
            }

            @Override
            public SipMessage onSend(SipMessage message, TransportAddress destination) {
                trace(">>", destination.toString(), message);
                return message;
            }
        });
    }

    /** Applies command line media / automation settings. */
    void configure(String audio, String record, String autoAnswer, String trace) {
        if (audio != null) {
            execute("audio " + audio);
        } else if (JavaSoundAudio.available()) {
            sourceFactory = JavaSoundAudio.Microphone::new;
        }
        this.recordPattern = record;
        this.autoAnswerDelay = autoAnswer == null ? null : Double.parseDouble(autoAnswer);
        if (trace != null) {
            traceMode = trace;
        }
    }

    boolean quitRequested() {
        return quit;
    }

    String prompt() {
        Call c = current;
        String state = c == null || c.state() == CallState.ENDED ? "idle"
                : "#" + c.number() + " " + c.state().name().toLowerCase(Locale.ROOT) + (c.isOnHold() ? "/hold" : "");
        return "softphone(" + state + ")> ";
    }

    void print(String line) {
        out.accept("[" + LocalTime.now().format(TIME) + "] " + line);
    }

    /**
     * Executes one command line.
     *
     * @return false if the command failed (used for exit codes in batch mode)
     */
    boolean execute(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return true;
        }
        String[] parts = trimmed.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            return switch (cmd) {
                case "help", "?" -> help();
                case "status" -> status();
                case "register" -> report("REGISTER", phone.register().get(35, TimeUnit.SECONDS));
                case "unregister" -> report("unREGISTER", phone.unregister().get(35, TimeUnit.SECONDS));
                case "call", "dial" -> call(arg);
                case "answer" -> answer(requireCall());
                case "reject" -> {
                    requireCall().reject(arg.isEmpty() ? 486 : Integer.parseInt(arg));
                    yield true;
                }
                case "hangup", "bye" -> {
                    requireCall().hangup();
                    yield true;
                }
                case "hold" -> {
                    requireCall().hold().get(35, TimeUnit.SECONDS);
                    print("Call on hold");
                    yield true;
                }
                case "resume", "unhold" -> {
                    requireCall().resume().get(35, TimeUnit.SECONDS);
                    print("Call resumed");
                    yield true;
                }
                case "dtmf" -> {
                    requireCall().sendDtmf(arg).get(30, TimeUnit.SECONDS);
                    print("Sent DTMF " + arg);
                    yield true;
                }
                case "info-dtmf" -> report("INFO", requireCall().sendDtmfInfo(arg.charAt(0), 250)
                        .get(35, TimeUnit.SECONDS));
                case "msg" -> {
                    String[] p = arg.split("\\s+", 2);
                    yield report("MESSAGE", phone.sendMessage(p[0], p.length > 1 ? p[1] : "")
                            .get(35, TimeUnit.SECONDS));
                }
                case "say" -> report("MESSAGE", requireCall().sendMessage(arg).get(35, TimeUnit.SECONDS));
                case "options", "ping" -> report("OPTIONS", phone.options(arg).get(35, TimeUnit.SECONDS));
                case "calls" -> calls();
                case "select" -> select(Long.parseLong(arg));
                case "audio" -> audio(arg);
                case "record" -> {
                    recordPattern = arg.isEmpty() || arg.equals("off") ? null : arg;
                    print(recordPattern == null ? "Recording off" : "Recording calls to " + recordPattern);
                    yield true;
                }
                case "trace" -> {
                    traceMode = arg.isEmpty() ? "short" : arg.toLowerCase(Locale.ROOT).replace("on", "short");
                    print("SIP trace " + traceMode);
                    yield true;
                }
                case "wait", "sleep" -> {
                    Thread.sleep((long) (Double.parseDouble(arg) * 1000));
                    yield true;
                }
                case "await" -> await(arg);
                case "expect-dtmf" -> expectDtmf(arg);
                case "echo" -> {
                    print(arg);
                    yield true;
                }
                case "quit", "exit" -> {
                    quit = true;
                    yield true;
                }
                default -> {
                    print("Unknown command '" + cmd + "' (type 'help')");
                    yield false;
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null && e instanceof java.util.concurrent.ExecutionException
                    ? e.getCause() : e;
            print("Error: " + cause.getMessage());
            return false;
        }
    }

    private boolean help() {
        print("""
                Commands:
                  call <uri|user@host:port>   place a call               answer / reject [code] / hangup
                  hold / resume               re-INVITE hold             dtmf <digits> (RFC 4733)
                  info-dtmf <digit>           DTMF via SIP INFO          say <text>  (MESSAGE in call)
                  msg <uri> <text>            SIP MESSAGE                options <uri> (ping)
                  register / unregister       registrar binding          status / calls / select <n>
                  audio device|silence|tone[:hz]|file:<wav>|echo         record <file.wav>|off
                  trace short|full|off        print SIP messages
                Automation:
                  wait <sec>   await connected|ringing|incoming|ended|registered [timeoutSec]
                  expect-dtmf <digits> [timeoutSec]   echo <text>   quit""");
        return true;
    }

    private boolean status() {
        print("Local URI: " + phone.localUri() + "   AOR: " + phone.aor());
        print("Registration: " + phone.registrationState());
        return calls();
    }

    private boolean calls() {
        List<Call> calls = phone.calls();
        if (calls.isEmpty()) {
            print("No active calls");
        }
        for (Call c : calls) {
            print((c == current ? "* " : "  ") + "#" + c.number() + " " + c.direction() + " " + c.remoteParty()
                    + " " + c.state() + (c.isOnHold() ? " [hold]" : "") + (c.isRemoteHold() ? " [remote hold]" : "")
                    + c.audioFormat().map(f -> " " + f.encodingName() + "/" + f.clockRate()).orElse("")
                    + c.audioStream().map(a -> " rtp tx=" + a.packetsSent() + " rx=" + a.packetsReceived()
                    + " lost=" + a.packetsLost()).orElse(""));
        }
        return true;
    }

    private boolean select(long number) {
        for (Call c : phone.calls()) {
            if (c.number() == number) {
                current = c;
                print("Selected call #" + number);
                return true;
            }
        }
        print("No call #" + number);
        return false;
    }

    private boolean call(String target) {
        if (target.isEmpty()) {
            print("usage: call <uri>");
            return false;
        }
        Call call = phone.call(target, CallOptions.audioOnly());
        attach(call);
        current = call;
        print("Calling " + call.remoteParty() + " (call #" + call.number() + ")");
        return true;
    }

    private boolean answer(Call call) {
        try {
            call.answer(CallOptions.audioOnly()).get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            print("Answer failed: " + e.getMessage());
            return false;
        }
    }

    private void attach(Call call) {
        AudioSource source = sourceFactory.get();
        call.setAudioSource(source);
        call.setAudioSink(source instanceof EchoAudio echo && recordPattern == null ? echo : createSink(call));
        call.addListener(new CallListener() {
            @Override
            public void onStateChanged(Call c, CallState state) {
                if (state != CallState.ENDED) {
                    print("Call #" + c.number() + " " + state + c.audioFormat()
                            .map(f -> " (" + f.encodingName() + "/" + f.clockRate() + ")").orElse(""));
                }
            }

            @Override
            public void onHoldChanged(Call c, boolean localHold, boolean remoteHold) {
                print("Call #" + c.number() + " hold: local=" + localHold + " remote=" + remoteHold);
            }

            @Override
            public void onDtmf(Call c, char digit, int durationMillis) {
                receivedDtmf.add(digit);
                print("Call #" + c.number() + " DTMF received: " + digit + " (" + durationMillis + " ms)");
            }

            @Override
            public void onMessage(Call c, String contentType, String body) {
                print("Call #" + c.number() + " message: " + body);
            }

            @Override
            public void onEnded(Call c, TerminationReason reason) {
                print("Call #" + c.number() + " ended: " + reason.cause()
                        + (reason.statusCode() > 0 ? " " + reason.statusCode() : "") + " " + reason.reason());
            }
        });
    }

    private AudioSink createSink(Call call) {
        String pattern = recordPattern;
        if (pattern != null) {
            try {
                return new WavFileSink(Path.of(pattern.replace("%n", String.valueOf(call.number()))));
            } catch (IOException | RuntimeException e) {
                print("Cannot record: " + e.getMessage());
            }
        }
        return JavaSoundAudio.available() ? new JavaSoundAudio.Speaker() : new NullSink();
    }

    private boolean audio(String spec) {
        String s = spec.trim();
        Supplier<AudioSource> factory;
        if (s.equals("device")) {
            factory = JavaSoundAudio.Microphone::new;
        } else if (s.equals("silence")) {
            factory = SilenceSource::new;
        } else if (s.startsWith("tone")) {
            double hz = s.contains(":") ? Double.parseDouble(s.substring(s.indexOf(':') + 1)) : 440;
            factory = () -> new ToneSource(hz, 0.3);
        } else if (s.startsWith("file:")) {
            Path path = Path.of(s.substring(5));
            factory = () -> {
                try {
                    return new WavFileSource(path, true);
                } catch (IOException e) {
                    throw new IllegalArgumentException("cannot read " + path + ": " + e.getMessage(), e);
                }
            };
            factory.get().close(); // validate now
        } else if (s.equals("echo")) {
            factory = EchoAudio::new;
        } else {
            print("usage: audio device|silence|tone[:hz]|file:<wav>|echo");
            return false;
        }
        sourceFactory = factory;
        Call c = current;
        if (c != null && c.state() != CallState.ENDED) {
            AudioSource source = factory.get();
            c.setAudioSource(source);
            if (source instanceof EchoAudio echo) {
                c.setAudioSink(echo);
            }
        }
        print("Audio source: " + s);
        return true;
    }

    private boolean await(String arg) throws Exception {
        String[] p = arg.split("\\s+");
        String what = p[0].toLowerCase(Locale.ROOT);
        long timeout = p.length > 1 ? (long) (Double.parseDouble(p[1]) * 1000) : 30_000;
        if (what.equals("registered")) {
            long deadline = System.currentTimeMillis() + timeout;
            while (phone.registrationState() != RegistrationState.REGISTERED) {
                if (System.currentTimeMillis() > deadline) {
                    print("Timed out waiting for registration");
                    return false;
                }
                Thread.sleep(50);
            }
            return true;
        }
        CallState state = switch (what) {
            case "connected", "answered" -> CallState.CONNECTED;
            case "ringing" -> CallState.RINGING;
            case "incoming" -> CallState.INCOMING;
            case "ended", "hangup" -> CallState.ENDED;
            case "early", "early_media" -> CallState.EARLY_MEDIA;
            default -> throw new IllegalArgumentException("unknown state " + what);
        };
        long deadline = System.currentTimeMillis() + timeout;
        while (current == null || (state == CallState.INCOMING && current.state() == CallState.ENDED)) {
            if (System.currentTimeMillis() > deadline) {
                print("Timed out waiting for a call");
                return false;
            }
            Thread.sleep(20);
        }
        try {
            current.awaitState(state, Duration.ofMillis(Math.max(1, deadline - System.currentTimeMillis())));
            print("Reached " + state);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            print("Timed out waiting for " + state + " (state " + current.state() + ")");
            return false;
        } catch (IllegalStateException e) {
            print(e.getMessage());
            return false;
        }
    }

    private boolean expectDtmf(String arg) throws InterruptedException {
        String[] p = arg.split("\\s+");
        String expected = p[0];
        long timeout = p.length > 1 ? (long) (Double.parseDouble(p[1]) * 1000) : 10_000;
        long deadline = System.currentTimeMillis() + timeout;
        StringBuilder got = new StringBuilder();
        while (got.length() < expected.length()) {
            Character c = receivedDtmf.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (c == null) {
                print("Expected DTMF '" + expected + "' but got '" + got + "'");
                return false;
            }
            got.append(c);
        }
        boolean ok = got.toString().equals(expected);
        print(ok ? "DTMF '" + expected + "' received" : "Expected DTMF '" + expected + "' but got '" + got + "'");
        return ok;
    }

    private Call requireCall() {
        Call c = current;
        if (c == null || c.state() == CallState.ENDED) {
            List<Call> active = phone.calls();
            if (active.isEmpty()) {
                throw new IllegalStateException("no active call");
            }
            c = active.getLast();
            current = c;
        }
        return c;
    }

    private boolean report(String what, SipResponse r) {
        print(what + " -> " + r.statusCode() + " " + r.reasonPhrase());
        return r.isSuccess();
    }

    private void trace(String arrow, String peer, SipMessage m) {
        String mode = traceMode;
        if (mode.equals("off")) {
            return;
        }
        if (mode.equals("full")) {
            print(arrow + " " + peer + "\n" + m);
        } else {
            print(arrow + " " + peer + "  " + m.startLine());
        }
    }

    @Override
    public void close() {
        for (Call c : phone.calls()) {
            c.hangup();
        }
    }
}
