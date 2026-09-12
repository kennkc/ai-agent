package com.agent.limb.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 四肢层：工具调用与执行
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.65.1)",
    comments = "Source: limb/v1/limb.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class LimbServiceGrpc {

  private LimbServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "limb.v1.LimbService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.agent.limb.v1.ExecuteToolRequest,
      com.agent.limb.v1.ExecuteToolResponse> getExecuteToolMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ExecuteTool",
      requestType = com.agent.limb.v1.ExecuteToolRequest.class,
      responseType = com.agent.limb.v1.ExecuteToolResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.limb.v1.ExecuteToolRequest,
      com.agent.limb.v1.ExecuteToolResponse> getExecuteToolMethod() {
    io.grpc.MethodDescriptor<com.agent.limb.v1.ExecuteToolRequest, com.agent.limb.v1.ExecuteToolResponse> getExecuteToolMethod;
    if ((getExecuteToolMethod = LimbServiceGrpc.getExecuteToolMethod) == null) {
      synchronized (LimbServiceGrpc.class) {
        if ((getExecuteToolMethod = LimbServiceGrpc.getExecuteToolMethod) == null) {
          LimbServiceGrpc.getExecuteToolMethod = getExecuteToolMethod =
              io.grpc.MethodDescriptor.<com.agent.limb.v1.ExecuteToolRequest, com.agent.limb.v1.ExecuteToolResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ExecuteTool"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.limb.v1.ExecuteToolRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.limb.v1.ExecuteToolResponse.getDefaultInstance()))
              .setSchemaDescriptor(new LimbServiceMethodDescriptorSupplier("ExecuteTool"))
              .build();
        }
      }
    }
    return getExecuteToolMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.limb.v1.RegisterToolRequest,
      com.agent.limb.v1.RegisterToolResponse> getRegisterToolMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterTool",
      requestType = com.agent.limb.v1.RegisterToolRequest.class,
      responseType = com.agent.limb.v1.RegisterToolResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.limb.v1.RegisterToolRequest,
      com.agent.limb.v1.RegisterToolResponse> getRegisterToolMethod() {
    io.grpc.MethodDescriptor<com.agent.limb.v1.RegisterToolRequest, com.agent.limb.v1.RegisterToolResponse> getRegisterToolMethod;
    if ((getRegisterToolMethod = LimbServiceGrpc.getRegisterToolMethod) == null) {
      synchronized (LimbServiceGrpc.class) {
        if ((getRegisterToolMethod = LimbServiceGrpc.getRegisterToolMethod) == null) {
          LimbServiceGrpc.getRegisterToolMethod = getRegisterToolMethod =
              io.grpc.MethodDescriptor.<com.agent.limb.v1.RegisterToolRequest, com.agent.limb.v1.RegisterToolResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterTool"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.limb.v1.RegisterToolRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.limb.v1.RegisterToolResponse.getDefaultInstance()))
              .setSchemaDescriptor(new LimbServiceMethodDescriptorSupplier("RegisterTool"))
              .build();
        }
      }
    }
    return getRegisterToolMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static LimbServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LimbServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LimbServiceStub>() {
        @java.lang.Override
        public LimbServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LimbServiceStub(channel, callOptions);
        }
      };
    return LimbServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static LimbServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LimbServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LimbServiceBlockingStub>() {
        @java.lang.Override
        public LimbServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LimbServiceBlockingStub(channel, callOptions);
        }
      };
    return LimbServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static LimbServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LimbServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LimbServiceFutureStub>() {
        @java.lang.Override
        public LimbServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LimbServiceFutureStub(channel, callOptions);
        }
      };
    return LimbServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 四肢层：工具调用与执行
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 工具调用
     * </pre>
     */
    default void executeTool(com.agent.limb.v1.ExecuteToolRequest request,
        io.grpc.stub.StreamObserver<com.agent.limb.v1.ExecuteToolResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getExecuteToolMethod(), responseObserver);
    }

    /**
     * <pre>
     * 工具注册
     * </pre>
     */
    default void registerTool(com.agent.limb.v1.RegisterToolRequest request,
        io.grpc.stub.StreamObserver<com.agent.limb.v1.RegisterToolResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterToolMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service LimbService.
   * <pre>
   * 四肢层：工具调用与执行
   * </pre>
   */
  public static abstract class LimbServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return LimbServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service LimbService.
   * <pre>
   * 四肢层：工具调用与执行
   * </pre>
   */
  public static final class LimbServiceStub
      extends io.grpc.stub.AbstractAsyncStub<LimbServiceStub> {
    private LimbServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LimbServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LimbServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * 工具调用
     * </pre>
     */
    public void executeTool(com.agent.limb.v1.ExecuteToolRequest request,
        io.grpc.stub.StreamObserver<com.agent.limb.v1.ExecuteToolResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getExecuteToolMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 工具注册
     * </pre>
     */
    public void registerTool(com.agent.limb.v1.RegisterToolRequest request,
        io.grpc.stub.StreamObserver<com.agent.limb.v1.RegisterToolResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterToolMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service LimbService.
   * <pre>
   * 四肢层：工具调用与执行
   * </pre>
   */
  public static final class LimbServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<LimbServiceBlockingStub> {
    private LimbServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LimbServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LimbServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 工具调用
     * </pre>
     */
    public com.agent.limb.v1.ExecuteToolResponse executeTool(com.agent.limb.v1.ExecuteToolRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getExecuteToolMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 工具注册
     * </pre>
     */
    public com.agent.limb.v1.RegisterToolResponse registerTool(com.agent.limb.v1.RegisterToolRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterToolMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service LimbService.
   * <pre>
   * 四肢层：工具调用与执行
   * </pre>
   */
  public static final class LimbServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<LimbServiceFutureStub> {
    private LimbServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LimbServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LimbServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 工具调用
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.limb.v1.ExecuteToolResponse> executeTool(
        com.agent.limb.v1.ExecuteToolRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getExecuteToolMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 工具注册
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.limb.v1.RegisterToolResponse> registerTool(
        com.agent.limb.v1.RegisterToolRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterToolMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_EXECUTE_TOOL = 0;
  private static final int METHODID_REGISTER_TOOL = 1;

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
        case METHODID_EXECUTE_TOOL:
          serviceImpl.executeTool((com.agent.limb.v1.ExecuteToolRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.limb.v1.ExecuteToolResponse>) responseObserver);
          break;
        case METHODID_REGISTER_TOOL:
          serviceImpl.registerTool((com.agent.limb.v1.RegisterToolRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.limb.v1.RegisterToolResponse>) responseObserver);
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
          getExecuteToolMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.limb.v1.ExecuteToolRequest,
              com.agent.limb.v1.ExecuteToolResponse>(
                service, METHODID_EXECUTE_TOOL)))
        .addMethod(
          getRegisterToolMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.limb.v1.RegisterToolRequest,
              com.agent.limb.v1.RegisterToolResponse>(
                service, METHODID_REGISTER_TOOL)))
        .build();
  }

  private static abstract class LimbServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    LimbServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.agent.limb.v1.Limb.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("LimbService");
    }
  }

  private static final class LimbServiceFileDescriptorSupplier
      extends LimbServiceBaseDescriptorSupplier {
    LimbServiceFileDescriptorSupplier() {}
  }

  private static final class LimbServiceMethodDescriptorSupplier
      extends LimbServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    LimbServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (LimbServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new LimbServiceFileDescriptorSupplier())
              .addMethod(getExecuteToolMethod())
              .addMethod(getRegisterToolMethod())
              .build();
        }
      }
    }
    return result;
  }
}
