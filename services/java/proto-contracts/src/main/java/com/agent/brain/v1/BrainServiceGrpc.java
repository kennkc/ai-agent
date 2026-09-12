package com.agent.brain.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 大脑层：意图识别与任务规划
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.65.1)",
    comments = "Source: brain/v1/brain.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class BrainServiceGrpc {

  private BrainServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "brain.v1.BrainService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.agent.brain.v1.RecognizeIntentRequest,
      com.agent.brain.v1.RecognizeIntentResponse> getRecognizeIntentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RecognizeIntent",
      requestType = com.agent.brain.v1.RecognizeIntentRequest.class,
      responseType = com.agent.brain.v1.RecognizeIntentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.brain.v1.RecognizeIntentRequest,
      com.agent.brain.v1.RecognizeIntentResponse> getRecognizeIntentMethod() {
    io.grpc.MethodDescriptor<com.agent.brain.v1.RecognizeIntentRequest, com.agent.brain.v1.RecognizeIntentResponse> getRecognizeIntentMethod;
    if ((getRecognizeIntentMethod = BrainServiceGrpc.getRecognizeIntentMethod) == null) {
      synchronized (BrainServiceGrpc.class) {
        if ((getRecognizeIntentMethod = BrainServiceGrpc.getRecognizeIntentMethod) == null) {
          BrainServiceGrpc.getRecognizeIntentMethod = getRecognizeIntentMethod =
              io.grpc.MethodDescriptor.<com.agent.brain.v1.RecognizeIntentRequest, com.agent.brain.v1.RecognizeIntentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RecognizeIntent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.brain.v1.RecognizeIntentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.brain.v1.RecognizeIntentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BrainServiceMethodDescriptorSupplier("RecognizeIntent"))
              .build();
        }
      }
    }
    return getRecognizeIntentMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.brain.v1.PlanTaskRequest,
      com.agent.brain.v1.PlanTaskResponse> getPlanTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PlanTask",
      requestType = com.agent.brain.v1.PlanTaskRequest.class,
      responseType = com.agent.brain.v1.PlanTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.brain.v1.PlanTaskRequest,
      com.agent.brain.v1.PlanTaskResponse> getPlanTaskMethod() {
    io.grpc.MethodDescriptor<com.agent.brain.v1.PlanTaskRequest, com.agent.brain.v1.PlanTaskResponse> getPlanTaskMethod;
    if ((getPlanTaskMethod = BrainServiceGrpc.getPlanTaskMethod) == null) {
      synchronized (BrainServiceGrpc.class) {
        if ((getPlanTaskMethod = BrainServiceGrpc.getPlanTaskMethod) == null) {
          BrainServiceGrpc.getPlanTaskMethod = getPlanTaskMethod =
              io.grpc.MethodDescriptor.<com.agent.brain.v1.PlanTaskRequest, com.agent.brain.v1.PlanTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PlanTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.brain.v1.PlanTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.brain.v1.PlanTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BrainServiceMethodDescriptorSupplier("PlanTask"))
              .build();
        }
      }
    }
    return getPlanTaskMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static BrainServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BrainServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BrainServiceStub>() {
        @java.lang.Override
        public BrainServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BrainServiceStub(channel, callOptions);
        }
      };
    return BrainServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static BrainServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BrainServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BrainServiceBlockingStub>() {
        @java.lang.Override
        public BrainServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BrainServiceBlockingStub(channel, callOptions);
        }
      };
    return BrainServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static BrainServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BrainServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BrainServiceFutureStub>() {
        @java.lang.Override
        public BrainServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BrainServiceFutureStub(channel, callOptions);
        }
      };
    return BrainServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 大脑层：意图识别与任务规划
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 意图识别
     * </pre>
     */
    default void recognizeIntent(com.agent.brain.v1.RecognizeIntentRequest request,
        io.grpc.stub.StreamObserver<com.agent.brain.v1.RecognizeIntentResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRecognizeIntentMethod(), responseObserver);
    }

    /**
     * <pre>
     * 任务规划
     * </pre>
     */
    default void planTask(com.agent.brain.v1.PlanTaskRequest request,
        io.grpc.stub.StreamObserver<com.agent.brain.v1.PlanTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPlanTaskMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service BrainService.
   * <pre>
   * 大脑层：意图识别与任务规划
   * </pre>
   */
  public static abstract class BrainServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return BrainServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service BrainService.
   * <pre>
   * 大脑层：意图识别与任务规划
   * </pre>
   */
  public static final class BrainServiceStub
      extends io.grpc.stub.AbstractAsyncStub<BrainServiceStub> {
    private BrainServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BrainServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BrainServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * 意图识别
     * </pre>
     */
    public void recognizeIntent(com.agent.brain.v1.RecognizeIntentRequest request,
        io.grpc.stub.StreamObserver<com.agent.brain.v1.RecognizeIntentResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRecognizeIntentMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 任务规划
     * </pre>
     */
    public void planTask(com.agent.brain.v1.PlanTaskRequest request,
        io.grpc.stub.StreamObserver<com.agent.brain.v1.PlanTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPlanTaskMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service BrainService.
   * <pre>
   * 大脑层：意图识别与任务规划
   * </pre>
   */
  public static final class BrainServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<BrainServiceBlockingStub> {
    private BrainServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BrainServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BrainServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 意图识别
     * </pre>
     */
    public com.agent.brain.v1.RecognizeIntentResponse recognizeIntent(com.agent.brain.v1.RecognizeIntentRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRecognizeIntentMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 任务规划
     * </pre>
     */
    public com.agent.brain.v1.PlanTaskResponse planTask(com.agent.brain.v1.PlanTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPlanTaskMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service BrainService.
   * <pre>
   * 大脑层：意图识别与任务规划
   * </pre>
   */
  public static final class BrainServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<BrainServiceFutureStub> {
    private BrainServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BrainServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BrainServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 意图识别
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.brain.v1.RecognizeIntentResponse> recognizeIntent(
        com.agent.brain.v1.RecognizeIntentRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRecognizeIntentMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 任务规划
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.brain.v1.PlanTaskResponse> planTask(
        com.agent.brain.v1.PlanTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPlanTaskMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_RECOGNIZE_INTENT = 0;
  private static final int METHODID_PLAN_TASK = 1;

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
        case METHODID_RECOGNIZE_INTENT:
          serviceImpl.recognizeIntent((com.agent.brain.v1.RecognizeIntentRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.brain.v1.RecognizeIntentResponse>) responseObserver);
          break;
        case METHODID_PLAN_TASK:
          serviceImpl.planTask((com.agent.brain.v1.PlanTaskRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.brain.v1.PlanTaskResponse>) responseObserver);
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
          getRecognizeIntentMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.brain.v1.RecognizeIntentRequest,
              com.agent.brain.v1.RecognizeIntentResponse>(
                service, METHODID_RECOGNIZE_INTENT)))
        .addMethod(
          getPlanTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.brain.v1.PlanTaskRequest,
              com.agent.brain.v1.PlanTaskResponse>(
                service, METHODID_PLAN_TASK)))
        .build();
  }

  private static abstract class BrainServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    BrainServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.agent.brain.v1.Brain.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("BrainService");
    }
  }

  private static final class BrainServiceFileDescriptorSupplier
      extends BrainServiceBaseDescriptorSupplier {
    BrainServiceFileDescriptorSupplier() {}
  }

  private static final class BrainServiceMethodDescriptorSupplier
      extends BrainServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    BrainServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (BrainServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new BrainServiceFileDescriptorSupplier())
              .addMethod(getRecognizeIntentMethod())
              .addMethod(getPlanTaskMethod())
              .build();
        }
      }
    }
    return result;
  }
}
