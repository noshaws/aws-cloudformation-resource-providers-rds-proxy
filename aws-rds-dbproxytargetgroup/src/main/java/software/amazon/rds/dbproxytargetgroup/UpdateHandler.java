package software.amazon.rds.dbproxytargetgroup;

import java.util.ArrayList;
import java.util.List;

import com.amazonaws.services.rds.AmazonRDS;
import com.amazonaws.services.rds.AmazonRDSClientBuilder;
import com.amazonaws.services.rds.model.ConnectionPoolConfiguration;
import com.amazonaws.services.rds.model.DBProxyTarget;
import com.amazonaws.services.rds.model.DBProxyTargetGroup;
import com.amazonaws.services.rds.model.DeregisterDBProxyTargetsRequest;
import com.amazonaws.services.rds.model.DescribeDBProxyTargetGroupsRequest;
import com.amazonaws.services.rds.model.ModifyDBProxyTargetGroupRequest;
import com.amazonaws.services.rds.model.RegisterDBProxyTargetsRequest;
import com.google.common.collect.ImmutableList;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

public class UpdateHandler extends BaseHandler<CallbackContext> {
    private AmazonWebServicesClientProxy clientProxy;
    private AmazonRDS rdsClient;

    private static final String TIMED_OUT_MESSAGE = "Timed out waiting for ProxyTargetGroup to finish modification.";

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {

        final ResourceModel newModel = request.getDesiredResourceState();
        final ResourceModel oldModel = request.getPreviousResourceState();

        clientProxy = proxy;
        rdsClient = AmazonRDSClientBuilder.defaultClient();

        final CallbackContext currentContext = callbackContext == null ?
                                               CallbackContext.builder().stabilizationRetriesRemaining(Constants.NUMBER_OF_STATE_POLL_RETRIES).build() :
                                               callbackContext;

        // This Lambda will continually be re-invoked with the current state of the proxy, finally succeeding when deleted.
        return updateProxyAndUpdateProgress(newModel, oldModel, currentContext);
    }

    private ProgressEvent<ResourceModel, CallbackContext> updateProxyAndUpdateProgress(ResourceModel newModel,
                                                                                       ResourceModel oldModel,
                                                                                       CallbackContext callbackContext) {
        // This Lambda will continually be re-invoked with the current state of the proxy, finally succeeding when state stabilizes.
        if (callbackContext.getStabilizationRetriesRemaining() == 0) {
            throw new RuntimeException(TIMED_OUT_MESSAGE);
        }

        // Update target-group settings
        if (callbackContext.getTargetGroupStatus() == null) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                           .resourceModels(ImmutableList.of(oldModel, newModel))
                           .status(OperationStatus.IN_PROGRESS)
                           .callbackContext(CallbackContext.builder()
                                                           .targetGroupStatus(modifyProxyTargetGroup(oldModel, newModel))
                                                           .stabilizationRetriesRemaining(Constants.NUMBER_OF_STATE_POLL_RETRIES)
                                                           .build())
                           .build();
        }

        // Update registered databases
        if  (!callbackContext.isTargetsDeregistered()) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                           .resourceModels(ImmutableList.of(oldModel, newModel))
                           .status(OperationStatus.IN_PROGRESS)
                           .callbackContext(CallbackContext.builder()
                                                           .targetGroupStatus(callbackContext.getTargetGroupStatus())
                                                           .targetsDeregistered(deregisterOldTargets(oldModel, newModel))
                                                           .stabilizationRetriesRemaining(Constants.NUMBER_OF_STATE_POLL_RETRIES)
                                                           .build())
                           .build();
        }

        if (callbackContext.getTargets() == null) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                           .resourceModels(ImmutableList.of(oldModel, newModel))
                           .status(OperationStatus.IN_PROGRESS)
                           .callbackContext(CallbackContext.builder()
                                                           .targetGroupStatus(callbackContext.getTargetGroupStatus())
                                                           .targetsDeregistered(callbackContext.isTargetsDeregistered())
                                                           .targets(registerNewTargets(oldModel, newModel))
                                                           .stabilizationRetriesRemaining(Constants.NUMBER_OF_STATE_POLL_RETRIES)
                                                           .build())
                           .build();
        }

        return ProgressEvent.<ResourceModel, CallbackContext>builder()
                       .resourceModel(newModel)
                       .status(OperationStatus.SUCCESS)
                       .build();
    }

    private DBProxyTargetGroup modifyProxyTargetGroup(ResourceModel oldModel, ResourceModel newModel) {
        if (oldModel.equals(newModel)) {
            return new DBProxyTargetGroup();
        }

        ConnectionPoolConfigurationInfoFormat modelConnectionPoolConfig = newModel.getConnectionPoolConfigurationInfo();
        if (modelConnectionPoolConfig == null) {
            DescribeDBProxyTargetGroupsRequest describeRequest = new DescribeDBProxyTargetGroupsRequest()
                                                                         .withDBProxyName(newModel.getDbProxyName())
                                                                         .withTargetGroupName(newModel.getTargetGroupName());
            return clientProxy.injectCredentialsAndInvoke(describeRequest, rdsClient::describeDBProxyTargetGroups).getTargetGroups().get(0);
        }

        ConnectionPoolConfiguration connectionPoolConfiguration =
                new ConnectionPoolConfiguration()
                        .withMaxConnectionsPercent(modelConnectionPoolConfig.getMaxConnectionsPercent())
                        .withMaxIdleConnectionsPercent(modelConnectionPoolConfig.getMaxIdleConnectionsPercent())
                        .withConnectionBorrowTimeout(modelConnectionPoolConfig.getConnectionBorrowTimeout())
                        .withSessionPinningFilters(modelConnectionPoolConfig.getSessionPinningFilters())
                        .withInitQuery(modelConnectionPoolConfig.getInitQuery());

        ModifyDBProxyTargetGroupRequest request = new ModifyDBProxyTargetGroupRequest()
                                                          .withDBProxyName(newModel.getDbProxyName())
                                                          .withTargetGroupName("default")
                                                          .withConnectionPoolConfig(connectionPoolConfiguration);

        return clientProxy.injectCredentialsAndInvoke(request, rdsClient::modifyDBProxyTargetGroup).getDBProxyTargetGroup();
    }

    private List<DBProxyTarget> registerNewTargets(ResourceModel oldModel, ResourceModel newModel) {
        List<String> oldClusters = Utility.getClusters(oldModel);
        List<String> newClusters = Utility.getClusters(newModel);
        List<String> clustersToAdd =  Utility.listDifference(newClusters, oldClusters);

        List<String> oldInstances = Utility.getInstances(oldModel);
        List<String> newInstances = Utility.getInstances(newModel);
        List<String> instancesToAdd =  Utility.listDifference(newInstances, oldInstances);

        if (clustersToAdd.size() == 0 && instancesToAdd.size() == 0) {
            return new ArrayList<>();
        }

        RegisterDBProxyTargetsRequest registerRequest = new RegisterDBProxyTargetsRequest()
                                                                .withDBProxyName(newModel.getDbProxyName())
                                                                .withTargetGroupName(newModel.getTargetGroupName())
                                                                .withDBClusterIdentifiers(clustersToAdd)
                                                                .withDBInstanceIdentifiers(instancesToAdd);
        return clientProxy.injectCredentialsAndInvoke(registerRequest, rdsClient::registerDBProxyTargets).getDBProxyTargets();
    }

    private boolean deregisterOldTargets(ResourceModel oldModel, ResourceModel newModel) {
        List<String> oldClusters = Utility.getClusters(oldModel);
        List<String> newClusters = Utility.getClusters(newModel);
        List<String> clustersToRemove =  Utility.listDifference(oldClusters, newClusters);

        List<String> oldInstances = Utility.getInstances(oldModel);
        List<String> newInstances = Utility.getInstances(newModel);
        List<String> instancesToRemove =  Utility.listDifference(oldInstances, newInstances);

        if (clustersToRemove.size() ==0 && instancesToRemove.size() == 0) {
            return true;
        }

        DeregisterDBProxyTargetsRequest deregisterRequest = new DeregisterDBProxyTargetsRequest()
                                                                    .withDBProxyName(newModel.getDbProxyName())
                                                                    .withTargetGroupName(newModel.getTargetGroupName())
                                                                    .withDBClusterIdentifiers(clustersToRemove)
                                                                    .withDBInstanceIdentifiers(instancesToRemove);

        clientProxy.injectCredentialsAndInvoke(deregisterRequest, rdsClient::deregisterDBProxyTargets);
        return true;
    }
}
