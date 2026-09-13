package org.res.ai;

public class ShellProcessFinalResult {
    private final ShellProcessPartialResult output;
    private final int returnValue;

    public ShellProcessFinalResult(ShellProcessPartialResult output, int returnValue) {
        this.output = output;
        this.returnValue = returnValue;
    }

    public ShellProcessPartialResult getOutput() {
        return output;
    }

    public int getReturnValue() {
        return returnValue;
    }
}
