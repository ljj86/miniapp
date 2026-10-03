package cn.iocoder.yudao.server.simulation;

public final class SimException extends RuntimeException {
    public final int status;
    public final String code;
    public final boolean commitSecurityState;
    public SimException(int status, String code, String message) {
        this(status,code,message,false);
    }
    public SimException(int status, String code, String message, boolean commitSecurityState) {
        super(message); this.status = status; this.code = code; this.commitSecurityState = commitSecurityState;
    }
}
