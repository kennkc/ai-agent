package com.agent.session.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 会话服务：VS1 垂直切片中枢（输入→网关→会话→意图→检索→回答）
 * 字段定义对齐《D5-2 核心数据字典》§3.1 Session / Message 模型
 * DEBT-003: 存储为内存 Map（VS1 简化版），触发点 P4 完成时替换 Redis 持久化
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.65.1)",
    comments = "Source: session/v1/session.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class SessionServiceGrpc {

  private SessionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "session.v1.SessionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.agent.session.v1.CreateSessionRequest,
      com.agent.session.v1.CreateSessionResponse> getCreateSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateSession",
      requestType = com.agent.session.v1.CreateSessionRequest.class,
      responseType = com.agent.session.v1.CreateSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.session.v1.CreateSessionRequest,
      com.agent.session.v1.CreateSessionResponse> getCreateSessionMethod() {
    io.grpc.MethodDescriptor<com.agent.session.v1.CreateSessionRequest, com.agent.session.v1.CreateSessionResponse> getCreateSessionMethod;
    if ((getCreateSessionMethod = SessionServiceGrpc.getCreateSessionMethod) == null) {
      synchronized (SessionServiceGrpc.class) {
        if ((getCreateSessionMethod = SessionServiceGrpc.getCreateSessionMethod) == null) {
          SessionServiceGrpc.getCreateSessionMethod = getCreateSessionMethod =
              io.grpc.MethodDescriptor.<com.agent.session.v1.CreateSessionRequest, com.agent.session.v1.CreateSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.CreateSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.CreateSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SessionServiceMethodDescriptorSupplier("CreateSession"))
              .build();
        }
      }
    }
    return getCreateSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.session.v1.AppendMessageRequest,
      com.agent.session.v1.AppendMessageResponse> getAppendMessageMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AppendMessage",
      requestType = com.agent.session.v1.AppendMessageRequest.class,
      responseType = com.agent.session.v1.AppendMessageResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.session.v1.AppendMessageRequest,
      com.agent.session.v1.AppendMessageResponse> getAppendMessageMethod() {
    io.grpc.MethodDescriptor<com.agent.session.v1.AppendMessageRequest, com.agent.session.v1.AppendMessageResponse> getAppendMessageMethod;
    if ((getAppendMessageMethod = SessionServiceGrpc.getAppendMessageMethod) == null) {
      synchronized (SessionServiceGrpc.class) {
        if ((getAppendMessageMethod = SessionServiceGrpc.getAppendMessageMethod) == null) {
          SessionServiceGrpc.getAppendMessageMethod = getAppendMessageMethod =
              io.grpc.MethodDescriptor.<com.agent.session.v1.AppendMessageRequest, com.agent.session.v1.AppendMessageResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AppendMessage"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.AppendMessageRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.AppendMessageResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SessionServiceMethodDescriptorSupplier("AppendMessage"))
              .build();
        }
      }
    }
    return getAppendMessageMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.session.v1.GetSessionRequest,
      com.agent.session.v1.GetSessionResponse> getGetSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSession",
      requestType = com.agent.session.v1.GetSessionRequest.class,
      responseType = com.agent.session.v1.GetSessionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.session.v1.GetSessionRequest,
      com.agent.session.v1.GetSessionResponse> getGetSessionMethod() {
    io.grpc.MethodDescriptor<com.agent.session.v1.GetSessionRequest, com.agent.session.v1.GetSessionResponse> getGetSessionMethod;
    if ((getGetSessionMethod = SessionServiceGrpc.getGetSessionMethod) == null) {
      synchronized (SessionServiceGrpc.class) {
        if ((getGetSessionMethod = SessionServiceGrpc.getGetSessionMethod) == null) {
          SessionServiceGrpc.getGetSessionMethod = getGetSessionMethod =
              io.grpc.MethodDescriptor.<com.agent.session.v1.GetSessionRequest, com.agent.session.v1.GetSessionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.GetSessionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.GetSessionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SessionServiceMethodDescriptorSupplier("GetSession"))
              .build();
        }
      }
    }
    return getGetSessionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.session.v1.ListMessagesRequest,
      com.agent.session.v1.ListMessagesResponse> getListMessagesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListMessages",
      requestType = com.agent.session.v1.ListMessagesRequest.class,
      responseType = com.agent.session.v1.ListMessagesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.session.v1.ListMessagesRequest,
      com.agent.session.v1.ListMessagesResponse> getListMessagesMethod() {
    io.grpc.MethodDescriptor<com.agent.session.v1.ListMessagesRequest, com.agent.session.v1.ListMessagesResponse> getListMessagesMethod;
    if ((getListMessagesMethod = SessionServiceGrpc.getListMessagesMethod) == null) {
      synchronized (SessionServiceGrpc.class) {
        if ((getListMessagesMethod = SessionServiceGrpc.getListMessagesMethod) == null) {
          SessionServiceGrpc.getListMessagesMethod = getListMessagesMethod =
              io.grpc.MethodDescriptor.<com.agent.session.v1.ListMessagesRequest, com.agent.session.v1.ListMessagesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListMessages"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.ListMessagesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.ListMessagesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SessionServiceMethodDescriptorSupplier("ListMessages"))
              .build();
        }
      }
    }
    return getListMessagesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.session.v1.UpdateSessionStatusRequest,
      com.agent.session.v1.UpdateSessionStatusResponse> getUpdateSessionStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateSessionStatus",
      requestType = com.agent.session.v1.UpdateSessionStatusRequest.class,
      responseType = com.agent.session.v1.UpdateSessionStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.session.v1.UpdateSessionStatusRequest,
      com.agent.session.v1.UpdateSessionStatusResponse> getUpdateSessionStatusMethod() {
    io.grpc.MethodDescriptor<com.agent.session.v1.UpdateSessionStatusRequest, com.agent.session.v1.UpdateSessionStatusResponse> getUpdateSessionStatusMethod;
    if ((getUpdateSessionStatusMethod = SessionServiceGrpc.getUpdateSessionStatusMethod) == null) {
      synchronized (SessionServiceGrpc.class) {
        if ((getUpdateSessionStatusMethod = SessionServiceGrpc.getUpdateSessionStatusMethod) == null) {
          SessionServiceGrpc.getUpdateSessionStatusMethod = getUpdateSessionStatusMethod =
              io.grpc.MethodDescriptor.<com.agent.session.v1.UpdateSessionStatusRequest, com.agent.session.v1.UpdateSessionStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateSessionStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.UpdateSessionStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.session.v1.UpdateSessionStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SessionServiceMethodDescriptorSupplier("UpdateSessionStatus"))
              .build();
        }
      }
    }
    return getUpdateSessionStatusMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static SessionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SessionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SessionServiceStub>() {
        @java.lang.Override
        public SessionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SessionServiceStub(channel, callOptions);
        }
      };
    return SessionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static SessionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SessionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SessionServiceBlockingStub>() {
        @java.lang.Override
        public SessionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SessionServiceBlockingStub(channel, callOptions);
        }
      };
    return SessionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static SessionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SessionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SessionServiceFutureStub>() {
        @java.lang.Override
        public SessionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SessionServiceFutureStub(channel, callOptions);
        }
      };
    return SessionServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 会话服务：VS1 垂直切片中枢（输入→网关→会话→意图→检索→回答）
   * 字段定义对齐《D5-2 核心数据字典》§3.1 Session / Message 模型
   * DEBT-003: 存储为内存 Map（VS1 简化版），触发点 P4 完成时替换 Redis 持久化
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 创建会话
     * </pre>
     */
    default void createSession(com.agent.session.v1.CreateSessionRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.CreateSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * 追加消息（user 消息由网关转发；assistant 消息由大脑回写）
     * </pre>
     */
    default void appendMessage(com.agent.session.v1.AppendMessageRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.AppendMessageResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAppendMessageMethod(), responseObserver);
    }

    /**
     * <pre>
     * 获取会话详情
     * </pre>
     */
    default void getSession(com.agent.session.v1.GetSessionRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.GetSessionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSessionMethod(), responseObserver);
    }

    /**
     * <pre>
     * 拉取会话消息列表（历史上下文）
     * </pre>
     */
    default void listMessages(com.agent.session.v1.ListMessagesRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.ListMessagesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListMessagesMethod(), responseObserver);
    }

    /**
     * <pre>
     * 会话状态变更（archive/delete）
     * </pre>
     */
    default void updateSessionStatus(com.agent.session.v1.UpdateSessionStatusRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.UpdateSessionStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUpdateSessionStatusMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service SessionService.
   * <pre>
   * 会话服务：VS1 垂直切片中枢（输入→网关→会话→意图→检索→回答）
   * 字段定义对齐《D5-2 核心数据字典》§3.1 Session / Message 模型
   * DEBT-003: 存储为内存 Map（VS1 简化版），触发点 P4 完成时替换 Redis 持久化
   * </pre>
   */
  public static abstract class SessionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return SessionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service SessionService.
   * <pre>
   * 会话服务：VS1 垂直切片中枢（输入→网关→会话→意图→检索→回答）
   * 字段定义对齐《D5-2 核心数据字典》§3.1 Session / Message 模型
   * DEBT-003: 存储为内存 Map（VS1 简化版），触发点 P4 完成时替换 Redis 持久化
   * </pre>
   */
  public static final class SessionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<SessionServiceStub> {
    private SessionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SessionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SessionServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * 创建会话
     * </pre>
     */
    public void createSession(com.agent.session.v1.CreateSessionRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.CreateSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 追加消息（user 消息由网关转发；assistant 消息由大脑回写）
     * </pre>
     */
    public void appendMessage(com.agent.session.v1.AppendMessageRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.AppendMessageResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAppendMessageMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 获取会话详情
     * </pre>
     */
    public void getSession(com.agent.session.v1.GetSessionRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.GetSessionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSessionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 拉取会话消息列表（历史上下文）
     * </pre>
     */
    public void listMessages(com.agent.session.v1.ListMessagesRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.ListMessagesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListMessagesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 会话状态变更（archive/delete）
     * </pre>
     */
    public void updateSessionStatus(com.agent.session.v1.UpdateSessionStatusRequest request,
        io.grpc.stub.StreamObserver<com.agent.session.v1.UpdateSessionStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUpdateSessionStatusMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service SessionService.
   * <pre>
   * 会话服务：VS1 垂直切片中枢（输入→网关→会话→意图→检索→回答）
   * 字段定义对齐《D5-2 核心数据字典》§3.1 Session / Message 模型
   * DEBT-003: 存储为内存 Map（VS1 简化版），触发点 P4 完成时替换 Redis 持久化
   * </pre>
   */
  public static final class SessionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<SessionServiceBlockingStub> {
    private SessionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SessionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SessionServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 创建会话
     * </pre>
     */
    public com.agent.session.v1.CreateSessionResponse createSession(com.agent.session.v1.CreateSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 追加消息（user 消息由网关转发；assistant 消息由大脑回写）
     * </pre>
     */
    public com.agent.session.v1.AppendMessageResponse appendMessage(com.agent.session.v1.AppendMessageRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAppendMessageMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 获取会话详情
     * </pre>
     */
    public com.agent.session.v1.GetSessionResponse getSession(com.agent.session.v1.GetSessionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSessionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 拉取会话消息列表（历史上下文）
     * </pre>
     */
    public com.agent.session.v1.ListMessagesResponse listMessages(com.agent.session.v1.ListMessagesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListMessagesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 会话状态变更（archive/delete）
     * </pre>
     */
    public com.agent.session.v1.UpdateSessionStatusResponse updateSessionStatus(com.agent.session.v1.UpdateSessionStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUpdateSessionStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service SessionService.
   * <pre>
   * 会话服务：VS1 垂直切片中枢（输入→网关→会话→意图→检索→回答）
   * 字段定义对齐《D5-2 核心数据字典》§3.1 Session / Message 模型
   * DEBT-003: 存储为内存 Map（VS1 简化版），触发点 P4 完成时替换 Redis 持久化
   * </pre>
   */
  public static final class SessionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<SessionServiceFutureStub> {
    private SessionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SessionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SessionServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 创建会话
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.session.v1.CreateSessionResponse> createSession(
        com.agent.session.v1.CreateSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 追加消息（user 消息由网关转发；assistant 消息由大脑回写）
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.session.v1.AppendMessageResponse> appendMessage(
        com.agent.session.v1.AppendMessageRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAppendMessageMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 获取会话详情
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.session.v1.GetSessionResponse> getSession(
        com.agent.session.v1.GetSessionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSessionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 拉取会话消息列表（历史上下文）
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.session.v1.ListMessagesResponse> listMessages(
        com.agent.session.v1.ListMessagesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListMessagesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 会话状态变更（archive/delete）
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.session.v1.UpdateSessionStatusResponse> updateSessionStatus(
        com.agent.session.v1.UpdateSessionStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUpdateSessionStatusMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE_SESSION = 0;
  private static final int METHODID_APPEND_MESSAGE = 1;
  private static final int METHODID_GET_SESSION = 2;
  private static final int METHODID_LIST_MESSAGES = 3;
  private static final int METHODID_UPDATE_SESSION_STATUS = 4;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CREATE_SESSION:
          serviceImpl.createSession((com.agent.session.v1.CreateSessionRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.session.v1.CreateSessionResponse>) responseObserver);
          break;
        case METHODID_APPEND_MESSAGE:
          serviceImpl.appendMessage((com.agent.session.v1.AppendMessageRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.session.v1.AppendMessageResponse>) responseObserver);
          break;
        case METHODID_GET_SESSION:
          serviceImpl.getSession((com.agent.session.v1.GetSessionRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.session.v1.GetSessionResponse>) responseObserver);
          break;
        case METHODID_LIST_MESSAGES:
          serviceImpl.listMessages((com.agent.session.v1.ListMessagesRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.session.v1.ListMessagesResponse>) responseObserver);
          break;
        case METHODID_UPDATE_SESSION_STATUS:
          serviceImpl.updateSessionStatus((com.agent.session.v1.UpdateSessionStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.session.v1.UpdateSessionStatusResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getCreateSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.session.v1.CreateSessionRequest,
              com.agent.session.v1.CreateSessionResponse>(
                service, METHODID_CREATE_SESSION)))
        .addMethod(
          getAppendMessageMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.session.v1.AppendMessageRequest,
              com.agent.session.v1.AppendMessageResponse>(
                service, METHODID_APPEND_MESSAGE)))
        .addMethod(
          getGetSessionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.session.v1.GetSessionRequest,
              com.agent.session.v1.GetSessionResponse>(
                service, METHODID_GET_SESSION)))
        .addMethod(
          getListMessagesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.session.v1.ListMessagesRequest,
              com.agent.session.v1.ListMessagesResponse>(
                service, METHODID_LIST_MESSAGES)))
        .addMethod(
          getUpdateSessionStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.session.v1.UpdateSessionStatusRequest,
              com.agent.session.v1.UpdateSessionStatusResponse>(
                service, METHODID_UPDATE_SESSION_STATUS)))
        .build();
  }

  private static abstract class SessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    SessionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.agent.session.v1.SessionOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("SessionService");
    }
  }

  private static final class SessionServiceFileDescriptorSupplier
      extends SessionServiceBaseDescriptorSupplier {
    SessionServiceFileDescriptorSupplier() {}
  }

  private static final class SessionServiceMethodDescriptorSupplier
      extends SessionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    SessionServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (SessionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new SessionServiceFileDescriptorSupplier())
              .addMethod(getCreateSessionMethod())
              .addMethod(getAppendMessageMethod())
              .addMethod(getGetSessionMethod())
              .addMethod(getListMessagesMethod())
              .addMethod(getUpdateSessionStatusMethod())
              .build();
        }
      }
    }
    return result;
  }
}
