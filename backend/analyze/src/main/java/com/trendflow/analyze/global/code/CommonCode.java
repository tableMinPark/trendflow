package com.trendflow.analyze.global.code;

public enum CommonCode {
    DAUM("DAUM"), NAVER("NAVER");

    private String name;

    CommonCode(String name){
        this.name = name;
    }

    public String getName() {
        return this.name;
    }
}
