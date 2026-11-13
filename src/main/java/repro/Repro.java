package repro;

import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Creates many short-lived contexts that each own an isolate (SandboxPolicy.ISOLATED, implicit engine), runs a small
 * script that triggers background compilation, and closes the context with close(true).
 *
 * <pre>
 * args: contexts scenario closeMode [threads]
 *   scenario   work    build, filter, map, sort and serialise 2000 rows (about 20 ms)
 *              hot     a long numeric loop
 *              trivial returns a constant (never triggers compilation)
 *   closeMode  same    the worker thread closes the context after eval returned
 *              other   a second thread closes the context after eval returned
 *              cancel  a second thread closes the context while a script is still running
 * system properties: -Dopt.NAME=VALUE adds the context option NAME=VALUE (for example -Dopt.engine.CompilerThreads=1)
 * </pre>
 */
public final class Repro {

    private static final String WORK = "(function(){const rows=[];for(let i=0;i<2000;i++)rows.push({id:i,name:'n'+i,size:(i*37)%1000});"
                    + "const big=rows.filter(r=>r.size>500).map(r=>({...r,kb:r.size/1024})).sort((a,b)=>b.size-a.size);"
                    + "return JSON.stringify(big.slice(0,10));})()";
    private static final String HOT = "(function(){let s=0;for(let k=0;k<3000000;k++){s+=k%7;}return s;})()";

    private Repro() {
    }

    private static Context newContext() {
        Context.Builder b = Context.newBuilder("js").sandbox(SandboxPolicy.ISOLATED)
                        .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
                        .option("engine.MaxIsolateMemory", "32MB")
                        .option("sandbox.MaxCPUTime", "10s")
                        .option("sandbox.MaxStackFrames", "1000")
                        .option("js.load", "false")
                        .option("js.print", "false")
                        .allowHostAccess(HostAccess.ISOLATED)
                        .allowPolyglotAccess(PolyglotAccess.NONE)
                        .allowCreateThread(false)
                        .allowIO(IOAccess.NONE)
                        .allowNativeAccess(false)
                        .allowCreateProcess(false)
                        .allowEnvironmentAccess(EnvironmentAccess.NONE);
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("opt.")) {
                b.option(key.substring(4), System.getProperty(key));
            }
        }
        return b.build();
    }

    public static void main(String[] args) throws Exception {
        int contexts = Integer.parseInt(args[0]);
        String scenario = args[1];
        String closeMode = args[2];
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        String script = switch (scenario) {
            case "work" -> WORK;
            case "hot" -> HOT;
            default -> "1";
        };
        ExecutorService closer = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "closer");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger next = new AtomicInteger(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        long start = System.nanoTime();
        Thread[] workers = new Thread[threads];
        for (int w = 0; w < threads; w++) {
            workers[w] = new Thread(() -> {
                while (next.getAndIncrement() <= contexts) {
                    Context c = newContext();
                    try {
                        if (closeMode.equals("cancel")) {
                            closer.submit(() -> {
                                try {
                                    Thread.sleep(30);
                                    c.close(true);
                                } catch (Throwable ignored) {
                                    // the context may already be closed
                                }
                            });
                            c.eval("js", "while(true){}");
                        } else {
                            c.eval("js", script);
                        }
                        ok.incrementAndGet();
                    } catch (Throwable t) {
                        failed.incrementAndGet(); // expected for closeMode=cancel
                    }
                    try {
                        if (closeMode.equals("other")) {
                            closer.submit(() -> c.close(true)).get();
                        } else {
                            c.close(true);
                        }
                    } catch (Throwable t) {
                        failed.incrementAndGet();
                    }
                }
            }, "worker-" + w);
            workers[w].start();
        }
        for (Thread t : workers) {
            t.join();
        }
        System.out.println("DONE contexts=" + contexts + " ok=" + ok + " failed=" + failed + " ms=" + (System.nanoTime() - start) / 1_000_000);
    }
}
