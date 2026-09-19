package com.agent.tool.sandbox;

/** 沙箱后端（可插拔：Docker 强隔离 / 受限子进程降级） */
public interface SandboxBackend {

    /** 后端标识（与视图层展示的 sandbox_backend 字段同源） */
    String name();

    /** 当前是否可用（Docker 不可达时为 false，由 SandboxExecutor 落到降级后端） */
    boolean available();

    /** 是否为真隔离边界（false = 降级后端，结果须带 degraded=true） */
    boolean isolated();

    SandboxResult run(SandboxSpec spec);
}