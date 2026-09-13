package org.res.ai;

public class ShellProcessPartialResult {
    private final byte[] stdout;
    private final byte[] stderr;

    public ShellProcessPartialResult(byte[] stdout, byte[] stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public byte[] getStdoutOutput() {
        return stdout;
    }

    public byte[] getStderrOutput() {
        return stderr;
    }
}
