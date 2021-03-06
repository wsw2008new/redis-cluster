package com.linda.cluster.redis.keepalived.zk;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import com.linda.cluster.redis.common.Service;
import com.linda.cluster.redis.common.bean.ClusterStateBean;
import com.linda.cluster.redis.common.bean.CountBean;
import com.linda.cluster.redis.common.bean.HostAndPort;
import com.linda.cluster.redis.common.bean.RedisZkData;
import com.linda.cluster.redis.common.constant.RedisZkNodeConstant;
import com.linda.cluster.redis.common.utils.JSONUtils;
import com.linda.cluster.redis.common.utils.RedisGetChildrenCallback;
import com.linda.cluster.redis.common.utils.RedisGetNodeDataCallback;
import com.linda.cluster.redis.common.utils.RedisNodeDeleteCallback;
import com.linda.cluster.redis.common.utils.RedisReplicationLinkUtils;
import com.linda.cluster.redis.common.utils.RedisSetNodeDataCallback;
import com.linda.cluster.redis.common.utils.RedisZookeeperUtils;
import com.linda.cluster.redis.keepalived.redis.MultiRedisAlivedPingService;
import com.linda.cluster.redis.keepalived.redis.RedisAliveBase;
import com.linda.cluster.redis.keepalived.redis.RedisAlivedListener;
import com.linda.cluster.redis.keepalived.redis.SimpleRedisAliveNode;


public class RedisZkClusterAliveService implements Service,RedisAlivedListener{
	
	private ZooKeeper zooKeeper;
	
	private String zkBase;
	
	private String productName;

	private String clusterName;
	
	private int monitorId;
	
	private String monitorPathName = RedisZkNodeConstant.REDIS_PRODUCT_CUSTER_MONITOR_NODE;
	
	private String votePathName = RedisZkNodeConstant.REDIS_PORDUCT_CLUSTER_NODE_VOTE_NODE;
	
	private MultiRedisAlivedPingService redisNodePingService = new MultiRedisAlivedPingService();
	
	private ConcurrentHashMap<String, HostAndPort> monitorNodes = new ConcurrentHashMap<String, HostAndPort>();
	
	private AtomicBoolean isClusterMonitorMaster = new AtomicBoolean(false);
	
	private Logger logger = Logger.getLogger(RedisZkClusterAliveService.class);
	
	private List<String> monitors = new CopyOnWriteArrayList<String>();
	//获取集群节点列表和投票列表
	private RedisGetChildrenCallback redisGetClusterNodesCallback;
	//节点删除回调
	private RedisNodeDeleteCallback redisNodeVoteDelCallback;
	//获取节点数据回调
	private RedisGetNodeDataCallback redisGetNodeDataCallback;
	//设置节点数据回调
	private RedisSetNodeDataCallback redisSetNodeDataCallback;
	
	private int minVotes = 2;
	
	public RedisZkClusterAliveService(ZooKeeper zooKeeper,String zkBase,String productName,String clusterName){
		this(zooKeeper, zkBase, productName, clusterName, 5000);
	}
	
	public RedisZkClusterAliveService(ZooKeeper zooKeeper,String zkBase,String productName,String clusterName,int pingInterval){
		this.zooKeeper = zooKeeper;
		this.zkBase = zkBase;
		this.productName = productName;
		this.clusterName = clusterName;
		Random random = new Random();
		monitorId = Math.abs(random.nextInt(100000000));
		redisNodePingService.setInterval(pingInterval);
		redisGetClusterNodesCallback = new ClusterGetChildrenCallback(zooKeeper);
		redisNodeVoteDelCallback = new ClusterNodeDeleteCallback(zooKeeper);
		redisGetNodeDataCallback = new RedisNodeConfigDataCallback(zooKeeper);
		redisSetNodeDataCallback = new RedisNodeSetDataCallback(zooKeeper);
		logger.info("my monitorId:"+monitorId);
	}
	
	private EventType filterEvent(WatchedEvent event){
		EventType type = event.getType();
		KeeperState state = event.getState();
		if(state==KeeperState.SyncConnected){
			return type;
		}else if(state==KeeperState.Disconnected){
			this.doWithZkNotConnected();
		}
		return null;
	}
	
	private void doWithZkNotConnected(){
		logger.error("zk service not connected");
	}
	
	private void redisNodeDeleted(String node){
		HostAndPort hostAndPort = monitorNodes.get(node);
		if(hostAndPort!=null){
			SimpleRedisAliveNode alive = redisNodePingService.getByHostAndPort(hostAndPort);
			if(alive!=null){
				logger.error("redis node delete start to shutdown alive");
				alive.close();
				redisNodePingService.remodeNode(alive);
			}
		}
	}
	
	private void redisClusterDeleted(String cluster){
		if(cluster!=null&&cluster.endsWith(this.clusterName)){
			logger.error("redis cluster deleted start to shutdown");
			this.shutdown();
		}
	}
	
	//product->cluster->node->votes->4343 votes路径监控
	private Watcher redisVotesWatcher = new Watcher(){
		public void process(WatchedEvent event) {
			String path = event.getPath();
			logger.info("watcher vote:path:"+path);
			String redisNode = RedisZookeeperUtils.getClusterRedisNodeName(path);
			EventType eventType = RedisZkClusterAliveService.this.filterEvent(event);
			if(eventType!=null){
				if(eventType == EventType.NodeChildrenChanged){
					RedisZkClusterAliveService.this.shutdownOrStartupNodeByVotes(redisNode);
				}else if(eventType == EventType.NodeDeleted){
					RedisZkClusterAliveService.this.redisNodeDeleted(redisNode);
				}
			}
		}
	};
	
	//product->cluster data监控,集群master节点改变
	private Watcher redisClusterDataWatcher = new Watcher(){
		public void process(WatchedEvent event) {
			String path = event.getPath();
			String cluster = RedisZookeeperUtils.getClusterName(path);
			logger.info("watcher cluster:path:"+path);
			EventType eventType = RedisZkClusterAliveService.this.filterEvent(event);
			if(eventType!=null){
				Map<String, HostAndPort> copy = RedisReplicationLinkUtils.copy(monitorNodes);
				if(eventType==EventType.NodeDataChanged){//master改变
					//TODO
					RedisZkClusterAliveService.this.getClusterNodes();
					RedisZkClusterAliveService.this.checkAndChooseMaster(copy);
				}else if(eventType==EventType.NodeDeleted){//集群删除
					RedisZkClusterAliveService.this.redisClusterDeleted(cluster);
				}else if(eventType==EventType.NodeChildrenChanged){//集群节点删除或者掉线
					RedisZkClusterAliveService.this.getClusterNodes();
					RedisZkClusterAliveService.this.checkAndChooseMaster(copy);
				}
			}
		}
	};
	
	//集群节点redisnode数据data变化监控
	private Watcher redisNodeDataWatcher = new Watcher(){
		public void process(WatchedEvent event) {
			String path = event.getPath();
			String nodeName = RedisZookeeperUtils.getClusterRedisNodeName(path);
			logger.info("watcher node:path:"+path);
			EventType eventType = RedisZkClusterAliveService.this.filterEvent(event);
			if(eventType!=null){
				if(eventType==EventType.NodeDataChanged){
					//TODO
					RedisZkClusterAliveService.this.getRedisNodeData(nodeName);
				}else if(eventType==EventType.NodeDeleted){
					RedisZkClusterAliveService.this.redisNodeDeleted(nodeName);
				}
			}
		}
	};
	
	private Watcher redisClusterNodesWatcher = new Watcher(){
		public void process(WatchedEvent event) {
			String path = event.getPath();
			String cluster = RedisZookeeperUtils.getClusterName(path);
			logger.info("watcher cluster:path:"+path);
			EventType eventType = RedisZkClusterAliveService.this.filterEvent(event);
			if(eventType!=null){
				Map<String, HostAndPort> copy = RedisReplicationLinkUtils.copy(monitorNodes);
				if(eventType==EventType.NodeChildrenChanged){
					RedisZkClusterAliveService.this.getClusterNodes();
					RedisZkClusterAliveService.this.checkAndChooseMaster(copy);
				}else if(eventType==EventType.NodeDeleted){
					RedisZkClusterAliveService.this.redisClusterDeleted(cluster);
				}
			}
		}
	};
	
	private Watcher redisClusterMonitorMasterWatcher = new Watcher(){
		public void process(WatchedEvent event) {
			String path = event.getPath();
			String cluster = RedisZookeeperUtils.getClusterName(path);
			logger.info("watcher cluster:path:"+path);
			EventType eventType = RedisZkClusterAliveService.this.filterEvent(event);
			if(eventType!=null){
				RedisZkClusterAliveService.this.checkClusterMonitorMaster();
			}
		}
	};
	
	private void createMonitorNode(){
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,monitorPathName,RedisZkNodeConstant.REDIS_CLUSTER_MONITORS_MONITOR_NODE);
		byte[] data = String.valueOf(monitorId).getBytes();
		CountBean countBean = new CountBean();
		boolean createMonitorResult = RedisZookeeperUtils.zkCreateNode(zooKeeper, path, data, CreateMode.EPHEMERAL_SEQUENTIAL, countBean, new MonitorNodeCreateCallback(zooKeeper,CreateMode.EPHEMERAL_SEQUENTIAL));
		if(createMonitorResult){
			logger.info("create monitor @ "+path+" success :"+monitorId);
			this.checkClusterMonitorMaster();
		}else{
			logger.error("can't get monitors after create on cluster "+productName+"/"+clusterName);
		}
	}
	
	private void checkClusterMonitorMaster(){
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,monitorPathName);
		CountBean monitorMasterCountBean = new CountBean();
		monitors.clear();
		List<String> refreshMonitors = RedisZookeeperUtils.zkGetChildren(zooKeeper, path, redisClusterMonitorMasterWatcher, monitorMasterCountBean, redisGetClusterNodesCallback);
		logger.info("all monitors:"+refreshMonitors);
		monitors.addAll(refreshMonitors);
		this.checkMonitorMaster(monitors);
	}
	
	private ClusterStateBean getClusterStateAndMaster(){
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName);
		byte[] data = RedisZkNodeConstant.REDIS_NODE_NULL_DATA.getBytes();
		CountBean bean = new CountBean();
		RedisZkData zkData = RedisZookeeperUtils.zkGetNodeData(zooKeeper,redisClusterDataWatcher, path, data, bean, redisGetNodeDataCallback);
		if(zkData!=null&&zkData.getDataLength()>0){
			String conf = new String(zkData.getData());
			if(StringUtils.isNotBlank(conf)){
				ClusterStateBean stateBean = JSONUtils.fromJson(conf, ClusterStateBean.class);
				stateBean.setStat(zkData.getStat());
				return stateBean;
			}
		}
		return null;
	}
	
	private void checkMonitorMaster(List<String> monitors){
		String master = RedisZookeeperUtils.getMaster(monitors, RedisZkNodeConstant.REDIS_CLUSTER_MONITORS_MONITOR_NODE);
		logger.info("monitor master:"+master);
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,monitorPathName,master);
		byte[] data = RedisZkNodeConstant.REDIS_NODE_NULL_DATA.getBytes();
		CountBean countBean = new CountBean();
		RedisZkData zkData = RedisZookeeperUtils.zkGetNodeData(zooKeeper, null, path, data, countBean, redisGetNodeDataCallback);
		if(zkData!=null&&zkData.getData()!=null){
			int id = Integer.parseInt(new String(zkData.getData()));
			logger.info("monitor master data:"+id);
			if(monitorId==id){
				this.isClusterMonitorMaster.set(true);
				logger.info("monitor master me !!!!!!!!!!!!!!!!!! monitorId:"+monitorId);
				this.checkMonitorMasterToDoList();
			}else{
				this.isClusterMonitorMaster.set(false);
			}
		}else{
			this.isClusterMonitorMaster.set(false);
			logger.error("get monitor master data null "+master);
		}
	}
	
	//当节点宕机时，监控master也宕机，此时选举出来的master需要重新调整节点和集群状态
	private void checkMonitorMasterToDoList(){
		Set<String> nodes = monitorNodes.keySet();
		for(String node:nodes){
			this.shutdownOrStartupNodeByVotes(node);
		}
	}
	
	private void getClusterNodes(){
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName);
		CountBean countBean = new CountBean();
		List<String> redisNodes = RedisZookeeperUtils.zkGetChildren(zooKeeper, path, redisClusterNodesWatcher, countBean, redisGetClusterNodesCallback);
		for(String redisNode:redisNodes){
			if(redisNode.equals(monitorPathName)){
				continue;
			}
			boolean hasNode = this.getRedisNodeData(redisNode);
			if(hasNode){
				this.shutdownOrStartupNodeByVotes(redisNode);
			}
		}
	}
	
	private boolean getRedisNodeData(String redisNode){
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,redisNode);
		CountBean countBean = new CountBean();
		byte[] data = RedisZkNodeConstant.REDIS_NODE_NULL_DATA.getBytes();
		RedisZkData zkData = RedisZookeeperUtils.zkGetNodeData(zooKeeper, redisNodeDataWatcher, path, data, countBean, redisGetNodeDataCallback);
		if(zkData!=null&&zkData.getData()!=null&&zkData.getData().length>5){
			String jsonHostAndPortData = new String(zkData.getData());
			HostAndPort hostAndPort = JSONUtils.fromJson(jsonHostAndPortData, HostAndPort.class);
			hostAndPort.setStat(zkData.getStat());
			HostAndPort hap = monitorNodes.get(redisNode);
			if(hap!=null){
				hap.copyFileds(hostAndPort);
				return this.monitorRedisNode(hap);
			}else{
				if(hostAndPort!=null){
					return this.monitorRedisNode(hostAndPort);
				}
			}
		}
		return false;
	}
	
	private boolean monitorRedisNode(HostAndPort hostAndPort){
		try{
			if(redisNodePingService.getByHostAndPort(hostAndPort)==null){
				SimpleRedisAliveNode redisAliveNode = new SimpleRedisAliveNode();
				redisAliveNode.setRedisHost(hostAndPort);
				redisAliveNode.addRedisListener(this);
				monitorNodes.put(hostAndPort.getName(), hostAndPort);
				redisNodePingService.addRedisNode(redisAliveNode);
				redisAliveNode.connect();
			}else{
				redisNodePingService.getByHostAndPort(hostAndPort).connect();
			}
			return true;
		}catch(Exception e){
			logger.error("start monitor redis node error:"+e.getMessage()+" node:"+hostAndPort.getHost()+":"+hostAndPort.getPort()+" monitor:"+monitorId);
		}
		return false;
	}
	
	private int getRedisNodeVotes(String redisNode){
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,redisNode,votePathName);
		CountBean countBean = new CountBean();
		List<String> votes = RedisZookeeperUtils.zkGetChildren(zooKeeper, path, redisVotesWatcher, countBean, redisGetClusterNodesCallback);
		if(votes!=null){
			return votes.size();
		}
		return 0;
	}
	
	private void executeShutdownNode(String redisNode){
		HostAndPort hostAndPort = monitorNodes.get(redisNode);
		if(isClusterMonitorMaster.get()&&hostAndPort.isAlive()){
			Map<String, HostAndPort> copy = RedisReplicationLinkUtils.copy(monitorNodes);
			hostAndPort.setAlive(false);
			hostAndPort.setMaster(null);
			this.checkAndChooseMaster(copy);
		}
	}
	
	private int shutdownOrStartupNodeByVotes(String redisNode){
		int votes = this.getRedisNodeVotes(redisNode);
		if(monitors.size()>minVotes){
			if(votes>monitors.size()/2){
				this.executeShutdownNode(redisNode);
			}else if(votes<monitors.size()/2){
				this.executeStartupNode(redisNode);
			}
		}
		return votes;
	}
	
	private void executeStartupNode(String redisNode){
		HostAndPort hostAndPort = monitorNodes.get(redisNode);
		if(isClusterMonitorMaster.get()&&!hostAndPort.isAlive()){
			Map<String, HostAndPort> copy = RedisReplicationLinkUtils.copy(monitorNodes);
			hostAndPort.setMaster(null);
			hostAndPort.setAlive(true);
			this.checkAndChooseMaster(copy);
		}
	}
	
	private void loggerLink(HostAndPort head){
		HostAndPort p = head;
		StringBuilder sb = new StringBuilder();
		while(p!=null){
			sb.append("->");
			sb.append(p.getName());
			sb.append("[");
			sb.append(JSONUtils.toJson(p));
			sb.append("]");
			p = p.getNext();
		}
		logger.info("cluster link:"+sb.toString());
	}
	
	private void checkAndChooseMaster(Map<String, HostAndPort> beforeData){
		if(this.isClusterMonitorMaster.get()){
			ClusterStateBean clusterState = this.getClusterStateAndMaster();
			//清除连接
			RedisReplicationLinkUtils.clearLink(monitorNodes);
			//链接
			HostAndPort master = RedisReplicationLinkUtils.linkRedisNodes(monitorNodes, clusterState);
			
			this.loggerLink(master);
			
			List<HostAndPort> changed = RedisReplicationLinkUtils.getChanged(beforeData, monitorNodes);
			
			this.changeRedisNodeData(changed);
			//更改集群状态
			this.changeClusterState(clusterState, master);
		}
	}
	
	private void changeClusterState(ClusterStateBean clusterState,HostAndPort master){
		ClusterStateBean oldState = ClusterStateBean.copyState(clusterState);
		if(clusterState==null){
			clusterState = new ClusterStateBean();
			clusterState.setAlive(false);
			clusterState.setMaster(null);
			clusterState.setStat(new Stat());
		}
		if(master!=null){
			clusterState.setAlive(true);
			clusterState.setMaster(master.getName());
		}else{
			clusterState.setAlive(false);
			clusterState.setMaster(null);
		}
		if(!clusterState.equals(oldState)){
			logger.info("change cluster "+clusterName+" state:"+JSONUtils.toJson(clusterState));
			byte[] data = RedisZookeeperUtils.getBytes(clusterState);
			String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName);
			RedisZookeeperUtils.zkSetNodeData(zooKeeper, path, clusterState.getStat().getVersion(), data, new CountBean(), redisSetNodeDataCallback);
		}
	}
	
	private void logChange(Collection<HostAndPort> changes){
		String log = JSONUtils.toJson(changes);
		logger.info("changeNodes:"+log);
	}
	
	private void changeRedisNodeData(List<HostAndPort> changes){
		logChange(changes);
		for(final HostAndPort host:changes){
			new Thread(new Runnable(){
				public void run() {
					SimpleRedisAliveNode redisAlive = redisNodePingService.getByHostAndPort(host);
					if(redisAlive!=null){
						String master = host.getMaster();
						if(host.isAlive()){
							if(master!=null){
								HostAndPort masterHostAndPort = monitorNodes.get(master);
								if(masterHostAndPort!=null&&masterHostAndPort.isAlive()){
									boolean slaveOf = redisAlive.slaveOf(masterHostAndPort);
								}else{
									boolean slaveOf = redisAlive.slaveOf(null);
								}
							}else{
								boolean slaveOf = redisAlive.slaveOf(null);
							}
						}
						String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,host.getName());
						logger.info("changeData:"+host.getName()+" : "+JSONUtils.toJson(host));
						byte[] data = RedisZookeeperUtils.getBytes(host);
						RedisZookeeperUtils.zkSetNodeData(zooKeeper, path, host.getStat().getVersion(), data, new CountBean(), redisSetNodeDataCallback);	
					}else{
						//修正多个节点有检测不到的情况
						RedisZkClusterAliveService.this.monitorRedisNode(host);
					}
				}
			}).start();
		}
	}
	
	/**
	 * redis集群高可用启动
	 * 先加入监控节点集合
	 * 然后监控节点，更新监控状态，更新master slave关系
	 */
	@Override
	public void startup() {
		new Thread(new Runnable(){
			@Override
			public void run() {
				logger.info("start to startup monitor:"+monitorId);
				//启动监控节点ping thread
				RedisZkClusterAliveService.this.redisNodePingService.startup();
				//创建监控节点/base/product/cluseter/monitors/monitor-0
				RedisZkClusterAliveService.this.createMonitorNode();
				
				Map<String, HostAndPort> copy = RedisReplicationLinkUtils.copy(monitorNodes);
				//获取redis节点
				RedisZkClusterAliveService.this.getClusterNodes();
				//更新 master slave节点
				RedisZkClusterAliveService.this.checkAndChooseMaster(copy);
				logger.info("startup monitor:"+monitorId+" finish");
			}
		}).start();
	}
	
	@Override
	public void shutdown() {
		this.redisNodePingService.shutdown();
		this.monitorNodes.clear();
		this.monitors.clear();
	}
	
	private void voteReidsNodeDown(HostAndPort hostAndPort){
		if(hostAndPort.isAlive()){
			logger.info("vote node:"+JSONUtils.toJson(hostAndPort)+" down "+this.monitorId);
		}
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,hostAndPort.getName(),votePathName,String.valueOf(this.monitorId));
		byte[] data = RedisZkNodeConstant.REDIS_NODE_NULL_DATA.getBytes();
		boolean createNode = RedisZookeeperUtils.zkCreateNode(zooKeeper, path, data, CreateMode.EPHEMERAL, new CountBean(), new MonitorNodeCreateCallback(zooKeeper,CreateMode.EPHEMERAL));
		this.shutdownOrStartupNodeByVotes(hostAndPort.getName());
	}
	
	private void voteRedisNodeUp(HostAndPort hostAndPort){
		if(!hostAndPort.isAlive()){
			logger.info("vote node:"+JSONUtils.toJson(hostAndPort)+" up "+this.monitorId);
		}
		String path = RedisZookeeperUtils.genPath(zkBase,productName,clusterName,hostAndPort.getName(),votePathName,String.valueOf(this.monitorId));
		boolean deleteNode = RedisZookeeperUtils.zkDeleteNode(zooKeeper, 0, path, new CountBean(), redisNodeVoteDelCallback);
		this.shutdownOrStartupNodeByVotes(hostAndPort.getName());
	}
	
	/**
	 * listener事件，连接成功会响应该事件，方便添加到ping中，同时更改状态
	 * @param redis
	 */
	@Override
	public void onConnected(RedisAliveBase redis) {
		HostAndPort hostAndPort = redis.getRedisHost();
		this.voteRedisNodeUp(hostAndPort);
	}

	@Override
	public void onClose(RedisAliveBase redis) {
		HostAndPort host = redis.getRedisHost();
		this.voteReidsNodeDown(host);
	}

	@Override
	public void onException(RedisAliveBase redis, Exception e) {
		HostAndPort host = redis.getRedisHost();
		this.voteReidsNodeDown(host);
	}

	@Override
	public void onInfo(RedisAliveBase redis, String info) {
		if(info!=null){
			this.onConnected(redis);
		}
	}

	@Override
	public void onPing(RedisAliveBase redis) {
		this.onConnected(redis);
	}
}
