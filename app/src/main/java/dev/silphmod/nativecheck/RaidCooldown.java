package dev.silphmod.nativecheck;

final class RaidCooldown {
    private static final long[] SECONDS={30,60,204,300,420,480,720,900,1140,1320,1680,2100,2400,2700,3300,3480,3720,3900,4140,4680,5040,5520,5940,6420,6840,7020,7200};
    private static final double[] KM={1,2,5,7,10,12,18,26,42,65,81,100,220,250,374,410,460,500,546,683,780,885,983,1087,1184,1262,1300};
    static double allowedMeters(long elapsed){
        if(elapsed<0)return 0;if(elapsed>=7200000)return Double.POSITIVE_INFINITY;
        double seconds=elapsed/1000.0;if(seconds<SECONDS[0])return KM[0]*1000;
        for(int i=1;i<SECONDS.length;i++)if(seconds<=SECONDS[i])return (KM[i-1]+(KM[i]-KM[i-1])*(seconds-SECONDS[i-1])/(SECONDS[i]-SECONDS[i-1]))*1000;
        return 0;
    }
    private RaidCooldown(){ }
}
