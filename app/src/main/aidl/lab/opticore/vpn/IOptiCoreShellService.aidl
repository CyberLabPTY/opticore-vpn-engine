package lab.opticore.vpn;

interface IOptiCoreShellService {

    void destroy() = 16777114;

    int getUid() = 1;

    int getPid() = 2;

    String getIdentity() = 3;

    String readMemInfo() = 4;

    String readNetworkInfo() = 5;
}
