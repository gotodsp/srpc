package com.hex.zookeeper;

import com.google.common.base.Throwables;
import com.hex.common.exception.RpcException;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author: hs
 */
public class ZkUtil {
    private static final Logger log = LoggerFactory.getLogger(ZkUtil.class);

    private static final int BASE_SLEEP_TIME = 1000;
    private static final int MAX_RETRIES = 3;
    public static final String SEPERATOR = "/";
    public static final String ZK_REGISTER_ROOT_PATH = "/srpc";
    private static final Map<String, List<String>> SERVICE_ADDRESS_MAP = new ConcurrentHashMap<>();
    private static final Set<String> REGISTERED_PATH_SET = ConcurrentHashMap.newKeySet();
    private static CuratorFramework zkClient;
    private static final Object lock = new Object();

    private ZkUtil() {
    }

    /**
     * 添加持久化节点
     *
     * @param path node path
     */
    public static void createPersistentNode(CuratorFramework zkClient, String path) {
        try {
            if (REGISTERED_PATH_SET.contains(path) || zkClient.checkExists().forPath(path) != null) {
                log.info("The node already exists. The node is:[{}]", path);
            } else {
                zkClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
                log.info("The node was created successfully. The node is:[{}]", path);
            }
            REGISTERED_PATH_SET.add(path);
        } catch (Exception e) {
            log.error("create persistent node for path [{}] fail", path);
        }
    }

    /**
     * 获取节点下的所有路径
     *
     * @param rpcServiceName rpc service name
     * @return All child nodes under the specified node
     */
    public static List<String> getChildrenNodes(CuratorFramework zkClient, String rpcServiceName) {
        if (SERVICE_ADDRESS_MAP.containsKey(rpcServiceName)) {
            return SERVICE_ADDRESS_MAP.get(rpcServiceName);
        }
        List<String> result = null;
        String servicePath = ZK_REGISTER_ROOT_PATH + SEPERATOR + rpcServiceName;
        try {
            result = zkClient.getChildren().forPath(servicePath);
            SERVICE_ADDRESS_MAP.put(rpcServiceName, result);
            registerWatcher(rpcServiceName, zkClient);
        } catch (Exception e) {
            log.error("get children nodes for path [{}] fail", servicePath);
        }
        return result;
    }

    /**
     * 删除该机器所有注册的节点
     */
    public static void clearRegistry(CuratorFramework zkClient, InetSocketAddress inetSocketAddress) {
        REGISTERED_PATH_SET.stream().parallel().forEach(p -> {
            try {
                if (p.endsWith(inetSocketAddress.toString())) {
                    zkClient.delete().forPath(p);
                }
            } catch (Exception e) {
                log.error("clear registry for path [{}] fail", p);
            }
        });
        log.info("All registered services on the server are cleared:[{}]", REGISTERED_PATH_SET);
    }

    public static CuratorFramework getZkClient(String zookeeperAddress) {
        if (zkClient == null || zkClient.getState() != CuratorFrameworkState.STARTED) {
            synchronized (lock) {
                if (zkClient == null || zkClient.getState() != CuratorFrameworkState.STARTED) {
                    // 重试3次
                    RetryPolicy retryPolicy = new ExponentialBackoffRetry(BASE_SLEEP_TIME, MAX_RETRIES);
                    zkClient = CuratorFrameworkFactory.builder()
                            /*
                             * zookeeper服务端地址，多个server之间使用英文逗号分隔开
                             * example: "hadoop1:2181,hadoop2:2181,hadoop3:2181"
                             */
                            .connectString(zookeeperAddress)
                            .retryPolicy(retryPolicy)
                            .build();
                    zkClient.start();
                    try {
                        // 等待10秒
                        if (!zkClient.blockUntilConnected(10, TimeUnit.SECONDS)) {
                            throw new RpcException("Time out waiting to connect to ZK!");
                        }
                    } catch (InterruptedException e) {
                        log.error("{}", Throwables.getStackTraceAsString(e));
                        throw new RpcException();
                    }
                }
            }
        }
        return zkClient;
    }

    /**
     * 注册监听，监听节点下的变化
     *
     * @param rpcServiceName rpc service name
     */
    private static void registerWatcher(String rpcServiceName, CuratorFramework zkClient) throws Exception {
        String servicePath = ZK_REGISTER_ROOT_PATH + SEPERATOR + rpcServiceName;
        PathChildrenCache pathChildrenCache = new PathChildrenCache(zkClient, servicePath, true);
        PathChildrenCacheListener pathChildrenCacheListener = (curatorFramework, pathChildrenCacheEvent) -> {
            List<String> serviceAddresses = curatorFramework.getChildren().forPath(servicePath);
            SERVICE_ADDRESS_MAP.put(rpcServiceName, serviceAddresses);
        };
        pathChildrenCache.getListenable().addListener(pathChildrenCacheListener);
        pathChildrenCache.start();
    }

}