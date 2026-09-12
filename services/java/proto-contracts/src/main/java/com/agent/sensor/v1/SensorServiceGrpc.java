package com.agent.sensor.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 五官层：多渠道信息采集
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.65.1)",
    comments = "Source: sensor/v1/sensor.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class SensorServiceGrpc {

  private SensorServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "sensor.v1.SensorService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.agent.sensor.v1.CollectRequest,
      com.agent.sensor.v1.CollectResponse> getCollectMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Collect",
      requestType = com.agent.sensor.v1.CollectRequest.class,
      responseType = com.agent.sensor.v1.CollectResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.sensor.v1.CollectRequest,
      com.agent.sensor.v1.CollectResponse> getCollectMethod() {
    io.grpc.MethodDescriptor<com.agent.sensor.v1.CollectRequest, com.agent.sensor.v1.CollectResponse> getCollectMethod;
    if ((getCollectMethod = SensorServiceGrpc.getCollectMethod) == null) {
      synchronized (SensorServiceGrpc.class) {
        if ((getCollectMethod = SensorServiceGrpc.getCollectMethod) == null) {
          SensorServiceGrpc.getCollectMethod = getCollectMethod =
              io.grpc.MethodDescriptor.<com.agent.sensor.v1.CollectRequest, com.agent.sensor.v1.CollectResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Collect"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.sensor.v1.CollectRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.sensor.v1.CollectResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SensorServiceMethodDescriptorSupplier("Collect"))
              .build();
        }
      }
    }
    return getCollectMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.sensor.v1.RegisterChannelRequest,
      com.agent.sensor.v1.RegisterChannelResponse> getRegisterChannelMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterChannel",
      requestType = com.agent.sensor.v1.RegisterChannelRequest.class,
      responseType = com.agent.sensor.v1.RegisterChannelResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.sensor.v1.RegisterChannelRequest,
      com.agent.sensor.v1.RegisterChannelResponse> getRegisterChannelMethod() {
    io.grpc.MethodDescriptor<com.agent.sensor.v1.RegisterChannelRequest, com.agent.sensor.v1.RegisterChannelResponse> getRegisterChannelMethod;
    if ((getRegisterChannelMethod = SensorServiceGrpc.getRegisterChannelMethod) == null) {
      synchronized (SensorServiceGrpc.class) {
        if ((getRegisterChannelMethod = SensorServiceGrpc.getRegisterChannelMethod) == null) {
          SensorServiceGrpc.getRegisterChannelMethod = getRegisterChannelMethod =
              io.grpc.MethodDescriptor.<com.agent.sensor.v1.RegisterChannelRequest, com.agent.sensor.v1.RegisterChannelResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterChannel"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.sensor.v1.RegisterChannelRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.sensor.v1.RegisterChannelResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SensorServiceMethodDescriptorSupplier("RegisterChannel"))
              .build();
        }
      }
    }
    return getRegisterChannelMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.sensor.v1.ChannelHealthRequest,
      com.agent.sensor.v1.ChannelHealthResponse> getChannelHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ChannelHealth",
      requestType = com.agent.sensor.v1.ChannelHealthRequest.class,
      responseType = com.agent.sensor.v1.ChannelHealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.sensor.v1.ChannelHealthRequest,
      com.agent.sensor.v1.ChannelHealthResponse> getChannelHealthMethod() {
    io.grpc.MethodDescriptor<com.agent.sensor.v1.ChannelHealthRequest, com.agent.sensor.v1.ChannelHealthResponse> getChannelHealthMethod;
    if ((getChannelHealthMethod = SensorServiceGrpc.getChannelHealthMethod) == null) {
      synchronized (SensorServiceGrpc.class) {
        if ((getChannelHealthMethod = SensorServiceGrpc.getChannelHealthMethod) == null) {
          SensorServiceGrpc.getChannelHealthMethod = getChannelHealthMethod =
              io.grpc.MethodDescriptor.<com.agent.sensor.v1.ChannelHealthRequest, com.agent.sensor.v1.ChannelHealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ChannelHealth"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.sensor.v1.ChannelHealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.sensor.v1.ChannelHealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SensorServiceMethodDescriptorSupplier("ChannelHealth"))
              .build();
        }
      }
    }
    return getChannelHealthMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static SensorServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SensorServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SensorServiceStub>() {
        @java.lang.Override
        public SensorServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SensorServiceStub(channel, callOptions);
        }
      };
    return SensorServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static SensorServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SensorServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SensorServiceBlockingStub>() {
        @java.lang.Override
        public SensorServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SensorServiceBlockingStub(channel, callOptions);
        }
      };
    return SensorServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static SensorServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SensorServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SensorServiceFutureStub>() {
        @java.lang.Override
        public SensorServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SensorServiceFutureStub(channel, callOptions);
        }
      };
    return SensorServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 五官层：多渠道信息采集
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 触发采集（R1 动态感知）
     * </pre>
     */
    default void collect(com.agent.sensor.v1.CollectRequest request,
        io.grpc.stub.StreamObserver<com.agent.sensor.v1.CollectResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCollectMethod(), responseObserver);
    }

    /**
     * <pre>
     * 渠道注册
     * </pre>
     */
    default void registerChannel(com.agent.sensor.v1.RegisterChannelRequest request,
        io.grpc.stub.StreamObserver<com.agent.sensor.v1.RegisterChannelResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterChannelMethod(), responseObserver);
    }

    /**
     * <pre>
     * 渠道健康检查
     * </pre>
     */
    default void channelHealth(com.agent.sensor.v1.ChannelHealthRequest request,
        io.grpc.stub.StreamObserver<com.agent.sensor.v1.ChannelHealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getChannelHealthMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service SensorService.
   * <pre>
   * 五官层：多渠道信息采集
   * </pre>
   */
  public static abstract class SensorServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return SensorServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service SensorService.
   * <pre>
   * 五官层：多渠道信息采集
   * </pre>
   */
  public static final class SensorServiceStub
      extends io.grpc.stub.AbstractAsyncStub<SensorServiceStub> {
    private SensorServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SensorServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SensorServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * 触发采集（R1 动态感知）
     * </pre>
     */
    public void collect(com.agent.sensor.v1.CollectRequest request,
        io.grpc.stub.StreamObserver<com.agent.sensor.v1.CollectResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCollectMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 渠道注册
     * </pre>
     */
    public void registerChannel(com.agent.sensor.v1.RegisterChannelRequest request,
        io.grpc.stub.StreamObserver<com.agent.sensor.v1.RegisterChannelResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterChannelMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 渠道健康检查
     * </pre>
     */
    public void channelHealth(com.agent.sensor.v1.ChannelHealthRequest request,
        io.grpc.stub.StreamObserver<com.agent.sensor.v1.ChannelHealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getChannelHealthMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service SensorService.
   * <pre>
   * 五官层：多渠道信息采集
   * </pre>
   */
  public static final class SensorServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<SensorServiceBlockingStub> {
    private SensorServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SensorServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SensorServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 触发采集（R1 动态感知）
     * </pre>
     */
    public com.agent.sensor.v1.CollectResponse collect(com.agent.sensor.v1.CollectRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCollectMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 渠道注册
     * </pre>
     */
    public com.agent.sensor.v1.RegisterChannelResponse registerChannel(com.agent.sensor.v1.RegisterChannelRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterChannelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 渠道健康检查
     * </pre>
     */
    public com.agent.sensor.v1.ChannelHealthResponse channelHealth(com.agent.sensor.v1.ChannelHealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getChannelHealthMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service SensorService.
   * <pre>
   * 五官层：多渠道信息采集
   * </pre>
   */
  public static final class SensorServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<SensorServiceFutureStub> {
    private SensorServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SensorServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SensorServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 触发采集（R1 动态感知）
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.sensor.v1.CollectResponse> collect(
        com.agent.sensor.v1.CollectRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCollectMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 渠道注册
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.sensor.v1.RegisterChannelResponse> registerChannel(
        com.agent.sensor.v1.RegisterChannelRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterChannelMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 渠道健康检查
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.sensor.v1.ChannelHealthResponse> channelHealth(
        com.agent.sensor.v1.ChannelHealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getChannelHealthMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_COLLECT = 0;
  private static final int METHODID_REGISTER_CHANNEL = 1;
  private static final int METHODID_CHANNEL_HEALTH = 2;

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
        case METHODID_COLLECT:
          serviceImpl.collect((com.agent.sensor.v1.CollectRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.sensor.v1.CollectResponse>) responseObserver);
          break;
        case METHODID_REGISTER_CHANNEL:
          serviceImpl.registerChannel((com.agent.sensor.v1.RegisterChannelRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.sensor.v1.RegisterChannelResponse>) responseObserver);
          break;
        case METHODID_CHANNEL_HEALTH:
          serviceImpl.channelHealth((com.agent.sensor.v1.ChannelHealthRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.sensor.v1.ChannelHealthResponse>) responseObserver);
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
          getCollectMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.sensor.v1.CollectRequest,
              com.agent.sensor.v1.CollectResponse>(
                service, METHODID_COLLECT)))
        .addMethod(
          getRegisterChannelMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.sensor.v1.RegisterChannelRequest,
              com.agent.sensor.v1.RegisterChannelResponse>(
                service, METHODID_REGISTER_CHANNEL)))
        .addMethod(
          getChannelHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.sensor.v1.ChannelHealthRequest,
              com.agent.sensor.v1.ChannelHealthResponse>(
                service, METHODID_CHANNEL_HEALTH)))
        .build();
  }

  private static abstract class SensorServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    SensorServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.agent.sensor.v1.Sensor.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("SensorService");
    }
  }

  private static final class SensorServiceFileDescriptorSupplier
      extends SensorServiceBaseDescriptorSupplier {
    SensorServiceFileDescriptorSupplier() {}
  }

  private static final class SensorServiceMethodDescriptorSupplier
      extends SensorServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    SensorServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (SensorServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new SensorServiceFileDescriptorSupplier())
              .addMethod(getCollectMethod())
              .addMethod(getRegisterChannelMethod())
              .addMethod(getChannelHealthMethod())
              .build();
        }
      }
    }
    return result;
  }
}
