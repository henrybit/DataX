package com.alibaba.datax.plugin.writer.odpswriter.util;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.writer.odpswriter.Constant;
import com.alibaba.datax.plugin.writer.odpswriter.Key;
import com.alibaba.datax.plugin.writer.odpswriter.OdpsWriterErrorCode;
import com.aliyun.odps.*;
import com.aliyun.odps.account.Account;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.account.TaobaoAccount;
import com.aliyun.odps.task.SQLTask;
import com.aliyun.odps.tunnel.TableTunnel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OdpsUtil {
    private static final Logger LOG = LoggerFactory.getLogger(OdpsUtil.class);

    public static Odps initOdps(Configuration originalConfig) {
        String odpsServer = originalConfig.getNecessaryValue(Key.ODPS_SERVER,
                OdpsWriterErrorCode.ILLEGAL_VALUE);
        String accessId = originalConfig.getNecessaryValue(Key.ACCESS_ID,
                OdpsWriterErrorCode.ILLEGAL_VALUE);
        String accessKey = originalConfig.getNecessaryValue(Key.ACCESS_KEY,
                OdpsWriterErrorCode.ILLEGAL_VALUE);
        String project = originalConfig.getNecessaryValue(Key.PROJECT,
                OdpsWriterErrorCode.ILLEGAL_VALUE);

        String accountType = originalConfig.getString(Key.ACCOUNT_TYPE,
                Constant.DEFAULT_ACCOUNT_TYPE);

        Account account;
        if (accountType.equalsIgnoreCase(Constant.DEFAULT_ACCOUNT_TYPE)) {
            account = new AliyunAccount(accessId, accessKey);
        } else if (accountType.equalsIgnoreCase(Constant.TAOBAO_ACCOUNT_TYPE)) {
            account = new TaobaoAccount(accessId, accessKey);
        } else {
            throw new DataXException(OdpsWriterErrorCode.UNSUPPORTED_ACCOUNT_TYPE,
                    String.format("Unsupported account type:[%s].", accountType));
        }

        Odps odps = new Odps(account);
        odps.setDefaultProject(project);
        odps.setEndpoint(odpsServer);

        return odps;
    }

    public static void checkIfVirtualTable(Table table) {
        boolean isVirtualView = table.isVirtualView();
        if (isVirtualView) {
            throw new DataXException(
                    OdpsWriterErrorCode.UNSUPPORTED_COLUMN_TYPE,
                    String.format(
                            "Table:[%s] is virtual view, DataX not support to write data to it.",
                            table.getName()));
        }
    }

    public static Table initTable(Odps odps, Configuration originalConfig) {
        String tableName = originalConfig.getNecessaryValue(Key.TABLE,
                OdpsWriterErrorCode.ILLEGAL_VALUE);
        Table table;
        try {
            table = OdpsUtil.getTable(odps, tableName);
            originalConfig.set(Constant.IS_PARTITIONED_TABLE,
                    OdpsUtil.isPartitionedTable(table));
        } catch (Exception e) {
            LOG.error("Failed to init table.", e);
            throw new DataXException(OdpsWriterErrorCode.CHECK_TABLE_FAIL,
                    e);
        }

        return table;
    }

    // 检查分区是否存在，api 没有？
    // 处理逻辑是：如果分区存在，则先删除分区，再重建分区；如果分区不存在，则直接创建分区
    public static void truncatePartition(Table table, String partition, boolean isPartitionExist) {
        if (isPartitionExist) {
            try {
                table.deletePartition(new PartitionSpec(partition));
            } catch (OdpsException e) {
                LOG.error(String.format("Failed to delete partition:[%s].", partition), e);
                throw new DataXException(OdpsWriterErrorCode.DELETE_PARTITION_FAIL, e);
            }
        }

        try {
            table.createPartition(new PartitionSpec(partition));
        } catch (OdpsException e) {
            LOG.error(String.format("Failed to create partition:[%s], table:[%s].",
                    partition, table.getName()), e);
            throw new DataXException(OdpsWriterErrorCode.CREATE_PARTITION_FAIL, e);
        }

    }

    // TODO 确认表名称特殊字符处理规则，以及大小写敏感等问题
    public static void truncateTable(Odps odps, Table table) {
        String dropDdl = "truncate table " + table.getName() + ";";
        try {
            SQLTask.run(odps, dropDdl);
        } catch (OdpsException e) {
            LOG.error(String.format("Failed to truncate table. SQL:[%s].", dropDdl), e);
            throw new DataXException(OdpsWriterErrorCode.TRUNCATE_TABLE_FAIL, e);
        }
    }

    // TODO retry
    public static Table getTable(Odps odps, String tableName) {
        return odps.tables().get(tableName);
    }

    public static boolean isPartitionedTable(Table table) {
        TableSchema tableSchema = table.getSchema();

        return tableSchema.getPartitionColumns().size() > 0;
    }

    public static List<String> getTableAllPartitions(Table table, int retryTime) {
        List<Partition> tableAllPartitions = null;

        for (int i = 0; i < retryTime; i++) {
            try {
                tableAllPartitions = table.getPartitions();
            } catch (Exception e) {
                if (i < retryTime) {
                    LOG.warn("Try to get table:[{}] all partitions for [{}] times.",
                            table.getName(), i + 1);

                    try {
                        Thread.sleep((long) (Math.pow(2, i) * 1000));
                    } catch (InterruptedException unused) {
                    }
                    continue;
                } else {
                    throw new DataXException(OdpsWriterErrorCode.GET_PARTITION_FAIL,
                            String.format("Get all partitions in table:[%s] failed.", table.getName()));
                }
            }

            if (null != tableAllPartitions) {
                break;
            }
        }

        List<String> retPartitions = new ArrayList<String>();

        if (null != tableAllPartitions) {
            for (Partition partition : tableAllPartitions) {
                retPartitions.add(partition.getPartitionSpec().toString());
            }
        }
        return retPartitions;
    }

    public static List<Column> getTableAllColumns(Table table) {
        TableSchema tableSchema = table.getSchema();

        return tableSchema.getColumns();
    }

    public static List<OdpsType> getTableOriginalColumnTypeList(
            List<Column> columns) {
        List<OdpsType> tableOriginalColumnTypeList = new ArrayList<OdpsType>();

        for (Column column : columns) {
            tableOriginalColumnTypeList.add(column.getType());
        }

        return tableOriginalColumnTypeList;
    }

    public static List<String> getTableOriginalColumnNameList(
            List<Column> columns) {
        List<String> tableOriginalColumnNameList = new ArrayList<String>();

        for (Column column : columns) {
            tableOriginalColumnNameList.add(column.getName());
        }

        return tableOriginalColumnNameList;
    }

    public static String formatPartition(String partition) {
        if (StringUtils.isBlank(partition)) {
            throw new DataXException(OdpsWriterErrorCode.UNSUPPORTED_COLUMN_TYPE,
                    "bad partition which is blank.");
        } else {
            return partition.trim().replaceAll(" *= *", "=")
                    .replaceAll(" */ *", ",").replaceAll(" *, *", ",")
                    .replaceAll("'", "");
        }
    }

    public static List<String> formatPartitions(List<String> partitions) {
        if (null == partitions || partitions.isEmpty()) {
            return Collections.emptyList();
        } else {
            List<String> formattedPartitions = new ArrayList<String>();
            for (String partition : partitions) {
                formattedPartitions.add(formatPartition(partition));

            }
            return formattedPartitions;
        }
    }

    public static List<Integer> parsePosition(List<String> userConfiguredColumns,
                                              List<String> allColumnList) {
        List<Integer> retList = new ArrayList<Integer>();

        for (String column : userConfiguredColumns) {
            for (int i = 0, len = allColumnList.size(); i < len; i++) {
                if (allColumnList.get(i).equalsIgnoreCase(column)) {
                    retList.add(i);
                    break;
                }
            }
        }
        return retList;
    }

    // TODO retry
    public static TableTunnel.UploadSession createMasterSession(Odps odps,
                                                                Configuration originalConfig) {
        // 线下环境必填，线上环境不需要
        String tunnelServer = originalConfig.getString(Key.TUNNEL_SERVER);

        // 以下参数必填
        String project = originalConfig.getNecessaryValue(Key.PROJECT, OdpsWriterErrorCode.REQUIRED_VALUE);
        String tableName = originalConfig.getNecessaryValue(Key.TABLE, OdpsWriterErrorCode.REQUIRED_VALUE);

        TableTunnel tunnel = new TableTunnel(odps);
        if (StringUtils.isNotBlank(tunnelServer)) {
            tunnel.setEndpoint(tunnelServer);
        }

        boolean isPartitionedTable = originalConfig
                .getBool(Constant.IS_PARTITIONED_TABLE);

        TableTunnel.UploadSession uploadSession;

        try {
            if (isPartitionedTable) {
                String partition = originalConfig.getNecessaryValue(Key.PARTITION, OdpsWriterErrorCode.REQUIRED_VALUE);
                uploadSession = tunnel.createUploadSession(project, tableName,
                        new PartitionSpec(partition));
            } else {
                uploadSession = tunnel.createUploadSession(project, tableName);
            }
        } catch (Exception e) {
            LOG.error(String.format("Error while createUploadSession. project:[%s], table:[{}].",
                    project, tableName), e);
            throw new DataXException(OdpsWriterErrorCode.CREATE_MASTER_SESSION_FAIL, e);
        }

        return uploadSession;
    }

    // TODO retry
    public static TableTunnel.UploadSession getSlaveSession(Odps odps,
                                                            Configuration sliceConfig) {
        // 线下环境必填，线上环境不需要
        String tunnelServer = sliceConfig.getString(Key.TUNNEL_SERVER, null);

        // 以下参数必填
        String project = sliceConfig.getNecessaryValue(Key.PROJECT, OdpsWriterErrorCode.REQUIRED_VALUE);
        String tableName = sliceConfig.getNecessaryValue(Key.TABLE, OdpsWriterErrorCode.REQUIRED_VALUE);
        String uploadId = sliceConfig.getNecessaryValue(Constant.UPLOAD_ID, OdpsWriterErrorCode.REQUIRED_VALUE);


        boolean isPartitionedTable = sliceConfig
                .getBool(Constant.IS_PARTITIONED_TABLE);

        TableTunnel tunnel = new TableTunnel(odps);

        if (StringUtils.isNoneBlank(tunnelServer)) {
            tunnel.setEndpoint(tunnelServer);
        }

        TableTunnel.UploadSession uploadSession;

        try {
            if (isPartitionedTable) {
                String partition = sliceConfig.getNecessaryValue(Key.PARTITION, OdpsWriterErrorCode.REQUIRED_VALUE);
                uploadSession = tunnel.getUploadSession(project, tableName,
                        new PartitionSpec(partition), uploadId);
            } else {
                uploadSession = tunnel.getUploadSession(project, tableName, uploadId);
            }
        } catch (Exception e) {
            LOG.error(String.format("Error while getUploadSession. uploadId:[%s], project:[%s], table:[{}].", uploadId,
                    project, tableName), e);
            throw new DataXException(OdpsWriterErrorCode.GET_SLAVE_SESSION_FAIL, e);
        }

        return uploadSession;
    }

}
