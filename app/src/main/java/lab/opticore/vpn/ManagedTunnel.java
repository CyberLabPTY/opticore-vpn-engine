package lab.opticore.vpn;

import com.wireguard.android.backend.Tunnel;

public final class ManagedTunnel implements Tunnel {
    private final String name;
    private volatile State state = State.DOWN;

    public ManagedTunnel(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void onStateChange(State newState) {
        state = newState;
    }

    public State getObservedState() {
        return state;
    }
}
