package com.agent.body.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 躯体层：知识存储与检索
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.65.1)",
    comments = "Source: body/v1/body.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class BodyServiceGrpc {

  private BodyServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "body.v1.BodyService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.agent.body.v1.IngestRequest,
      com.agent.body.v1.IngestResponse> getIngestMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Ingest",
      requestType = com.agent.body.v1.IngestRequest.class,
      responseType = com.agent.body.v1.IngestResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.body.v1.IngestRequest,
      com.agent.body.v1.IngestResponse> getIngestMethod() {
    io.grpc.MethodDescriptor<com.agent.body.v1.IngestRequest, com.agent.body.v1.IngestResponse> getIngestMethod;
    if ((getIngestMethod = BodyServiceGrpc.getIngestMethod) == null) {
      synchronized (BodyServiceGrpc.class) {
        if ((getIngestMethod = BodyServiceGrpc.getIngestMethod) == null) {
          BodyServiceGrpc.getIngestMethod = getIngestMethod =
              io.grpc.MethodDescriptor.<com.agent.body.v1.IngestRequest, com.agent.body.v1.IngestResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Ingest"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.body.v1.IngestRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.body.v1.IngestResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BodyServiceMethodDescriptorSupplier("Ingest"))
              .build();
        }
      }
    }
    return getIngestMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.agent.body.v1.RetrieveRequest,
      com.agent.body.v1.RetrieveResponse> getRetrieveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Retrieve",
      requestType = com.agent.body.v1.RetrieveRequest.class,
      responseType = com.agent.body.v1.RetrieveResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.agent.body.v1.RetrieveRequest,
      com.agent.body.v1.RetrieveResponse> getRetrieveMethod() {
    io.grpc.MethodDescriptor<com.agent.body.v1.RetrieveRequest, com.agent.body.v1.RetrieveResponse> getRetrieveMethod;
    if ((getRetrieveMethod = BodyServiceGrpc.getRetrieveMethod) == null) {
      synchronized (BodyServiceGrpc.class) {
        if ((getRetrieveMethod = BodyServiceGrpc.getRetrieveMethod) == null) {
          BodyServiceGrpc.getRetrieveMethod = getRetrieveMethod =
              io.grpc.MethodDescriptor.<com.agent.body.v1.RetrieveRequest, com.agent.body.v1.RetrieveResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Retrieve"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.body.v1.RetrieveRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.agent.body.v1.RetrieveResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BodyServiceMethodDescriptorSupplier("Retrieve"))
              .build();
        }
      }
    }
    return getRetrieveMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static BodyServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BodyServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BodyServiceStub>() {
        @java.lang.Override
        public BodyServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BodyServiceStub(channel, callOptions);
        }
      };
    return BodyServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static BodyServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BodyServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BodyServiceBlockingStub>() {
        @java.lang.Override
        public BodyServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BodyServiceBlockingStub(channel, callOptions);
        }
      };
    return BodyServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static BodyServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BodyServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BodyServiceFutureStub>() {
        @java.lang.Override
        public BodyServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BodyServiceFutureStub(channel, callOptions);
        }
      };
    return BodyServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 躯体层：知识存储与检索
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 文档入库
     * </pre>
     */
    default void ingest(com.agent.body.v1.IngestRequest request,
        io.grpc.stub.StreamObserver<com.agent.body.v1.IngestResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getIngestMethod(), responseObserver);
    }

    /**
     * <pre>
     * 语义检索
     * </pre>
     */
    default void retrieve(com.agent.body.v1.RetrieveRequest request,
        io.grpc.stub.StreamObserver<com.agent.body.v1.RetrieveResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRetrieveMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service BodyService.
   * <pre>
   * 躯体层：知识存储与检索
   * </pre>
   */
  public static abstract class BodyServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return BodyServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service BodyService.
   * <pre>
   * 躯体层：知识存储与检索
   * </pre>
   */
  public static final class BodyServiceStub
      extends io.grpc.stub.AbstractAsyncStub<BodyServiceStub> {
    private BodyServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BodyServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BodyServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * 文档入库
     * </pre>
     */
    public void ingest(com.agent.body.v1.IngestRequest request,
        io.grpc.stub.StreamObserver<com.agent.body.v1.IngestResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getIngestMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 语义检索
     * </pre>
     */
    public void retrieve(com.agent.body.v1.RetrieveRequest request,
        io.grpc.stub.StreamObserver<com.agent.body.v1.RetrieveResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRetrieveMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service BodyService.
   * <pre>
   * 躯体层：知识存储与检索
   * </pre>
   */
  public static final class BodyServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<BodyServiceBlockingStub> {
    private BodyServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BodyServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BodyServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 文档入库
     * </pre>
     */
    public com.agent.body.v1.IngestResponse ingest(com.agent.body.v1.IngestRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getIngestMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 语义检索
     * </pre>
     */
    public com.agent.body.v1.RetrieveResponse retrieve(com.agent.body.v1.RetrieveRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRetrieveMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service BodyService.
   * <pre>
   * 躯体层：知识存储与检索
   * </pre>
   */
  public static final class BodyServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<BodyServiceFutureStub> {
    private BodyServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BodyServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BodyServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 文档入库
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.body.v1.IngestResponse> ingest(
        com.agent.body.v1.IngestRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getIngestMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 语义检索
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.agent.body.v1.RetrieveResponse> retrieve(
        com.agent.body.v1.RetrieveRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRetrieveMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_INGEST = 0;
  private static final int METHODID_RETRIEVE = 1;

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
        case METHODID_INGEST:
          serviceImpl.ingest((com.agent.body.v1.IngestRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.body.v1.IngestResponse>) responseObserver);
          break;
        case METHODID_RETRIEVE:
          serviceImpl.retrieve((com.agent.body.v1.RetrieveRequest) request,
              (io.grpc.stub.StreamObserver<com.agent.body.v1.RetrieveResponse>) responseObserver);
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
          getIngestMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.body.v1.IngestRequest,
              com.agent.body.v1.IngestResponse>(
                service, METHODID_INGEST)))
        .addMethod(
          getRetrieveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.agent.body.v1.RetrieveRequest,
              com.agent.body.v1.RetrieveResponse>(
                service, METHODID_RETRIEVE)))
        .build();
  }

  private static abstract class BodyServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    BodyServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.agent.body.v1.Body.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("BodyService");
    }
  }

  private static final class BodyServiceFileDescriptorSupplier
      extends BodyServiceBaseDescriptorSupplier {
    BodyServiceFileDescriptorSupplier() {}
  }

  private static final class BodyServiceMethodDescriptorSupplier
      extends BodyServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    BodyServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (BodyServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new BodyServiceFileDescriptorSupplier())
              .addMethod(getIngestMethod())
              .addMethod(getRetrieveMethod())
              .build();
        }
      }
    }
    return result;
  }
}
