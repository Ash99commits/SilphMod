package dev.silphmod.nativecheck;

final class RaidSources {
    private RaidSources(){ }
    static String url(int country){return switch(country){
        case 1->"https://assets-v2.pgtools.net/raids-d01c65b281508df16ba1dde3b60bbac9.json";
        case 2->"https://assets-v2.pgtools.net/raids-b78050e80b55eb97257248e45fdd5506.json";
        default->throw new IllegalArgumentException("Unknown raid country");
    };}
}
