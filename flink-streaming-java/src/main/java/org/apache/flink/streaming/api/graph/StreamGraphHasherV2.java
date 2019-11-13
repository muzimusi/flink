/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.graph;

import org.apache.flink.api.dag.Transformation;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.operators.UdfStreamOperatorFactory;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;

import org.apache.flink.shaded.guava18.com.google.common.hash.HashFunction;
import org.apache.flink.shaded.guava18.com.google.common.hash.Hasher;
import org.apache.flink.shaded.guava18.com.google.common.hash.Hashing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.apache.flink.util.StringUtils.byteToHexString;

/**
 * StreamGraphHasher from Flink 1.2. This contains duplicated code to ensure that the algorithm does not change with
 * future Flink versions.
 *
 * <p>DO NOT MODIFY THIS CLASS
 */
public class StreamGraphHasherV2 implements StreamGraphHasher {

	private static final Logger LOG = LoggerFactory.getLogger(StreamGraphHasherV2.class);

	/**
	 * Returns a map with a hash for each {@link StreamNode} of the {@link
	 * StreamGraph}. The hash is used as the {@link JobVertexID} in order to
	 * identify nodes across job submissions if they didn't change.
	 *
	 * <p>The complete {@link StreamGraph} is traversed. The hash is either
	 * computed from the transformation's user-specified id (see
	 * {@link Transformation#getUid()}) or generated in a deterministic way.
	 *
	 * <p>The generated hash is deterministic with respect to:
	 * <ul>
	 *   <li>node-local properties (node ID),
	 *   <li>chained output nodes, and
	 *   <li>input nodes hashes
	 * </ul>
	 *
	 * @return A map from {@link StreamNode#id} to hash as 16-byte array.
	 */
	// 遍历StreamGraph，根据用户指定的Uid或者使用flink特定的策略来生成hash
	// 生成策略：
	// 1、节点自身属性
	// 2、可以chain在一起的下游节点
	// 3、上游节点
	@Override
	public Map<Integer, byte[]> traverseStreamGraphAndGenerateHashes(StreamGraph streamGraph) {
		// The hash function used to generate the hash
		final HashFunction hashFunction = Hashing.murmur3_128(0);
		final Map<Integer, byte[]> hashes = new HashMap<>();

		// 辅助空间
		Set<Integer> visited = new HashSet<>();
		Queue<StreamNode> remaining = new ArrayDeque<>();

		// 根据id大小对source节点排序，【多source节点时，每次获取的source ids顺序不固定】
		// We need to make the source order deterministic. The source IDs are
		// not returned in the same order, which means that submitting the same
		// program twice might result in different traversal, which breaks the
		// deterministic hash assignment.
		List<Integer> sources = new ArrayList<>();
		for (Integer sourceNodeId : streamGraph.getSourceIDs()) {
			sources.add(sourceNodeId);
		}
		Collections.sort(sources);

		// 宽度优先遍历：(借助队列进行层次遍历)
		// 辅助空间： 1、辅助队列remaining 2、标记集合visited
		// 步骤：
		// 1.第一层为source节点，加入remaining队列，从队首开始遍历
		// 2.每次遍历后移除队首元素并进行mark，若队首元素存在outPut节点，将剩余节点加入remaining队列
		// 重复1-2步，知道全部元素遍历

		//
		// Traverse the graph in a breadth-first manner. Keep in mind that
		// the graph is not a tree and multiple paths to nodes can exist.
		//

		// Start with source nodes
		// source节点作为第一层开始遍历
		for (Integer sourceNodeId : sources) {
			remaining.add(streamGraph.getStreamNode(sourceNodeId));
			visited.add(sourceNodeId);
		}

		// 开始出队并检测
		StreamNode currentNode;
		while ((currentNode = remaining.poll()) != null) {
			// Generate the hash code. Because multiple path exist to each
			// node, we might not have all required inputs available to
			// generate the hash code.
			// 为当前遍历节点生成hash值，【hash生成策略】
			if (generateNodeHash(currentNode, hashFunction, hashes, streamGraph.isChainingEnabled(), streamGraph)) {
				// Add the child nodes
				// 当前节点可能有很多出边 【streamEdge包含SourceId和targetId】
				for (StreamEdge outEdge : currentNode.getOutEdges()) {
					// 当前节点是边的source，拿到边的target节点
					StreamNode child = streamGraph.getTargetVertex(outEdge);

					if (!visited.contains(child.getId())) {
						remaining.add(child);
						visited.add(child.getId());
					}
				}
			} else {//当前节点有上游节点还有没生成hash，或者当前节点hash生成失败
				// We will revisit this later.
				visited.remove(currentNode.getId());
			}
		}

		return hashes;
	}

	/**
	 * Generates a hash for the node and returns whether the operation was
	 * successful.
	 *
	 * @param node         The node to generate the hash for
	 * @param hashFunction The hash function to use
	 * @param hashes       The current state of generated hashes
	 * @return <code>true</code> if the node hash has been generated.
	 * <code>false</code>, otherwise. If the operation is not successful, the
	 * hash needs be generated at a later point when all input is available.
	 * @throws IllegalStateException If node has user-specified hash and is
	 *                               intermediate node of a chain
	 */
	private boolean generateNodeHash(
			StreamNode node,
			HashFunction hashFunction,
			Map<Integer, byte[]> hashes,
			boolean isChainingEnabled,
			StreamGraph streamGraph) {

		// Check for user-specified ID
		// 检查用户是否手动为该算子设置UID
		String userSpecifiedHash = node.getTransformationUID();

		// 没有手动指定uid
		// 根据生成策略进行校验 【因为，节点的hash值生成策略要兼顾node-local properties and input and output edges.】
		// 	1、节点生成hash值的前提：其所有input节点均已经生成hash值
		if (userSpecifiedHash == null) {
			// Check that all input nodes have their hashes computed
			for (StreamEdge inEdge : node.getInEdges()) {
				// If the input node has not been visited yet, the current
				// node will be visited again at a later point when all input
				// nodes have been visited and their hashes set.
				if (!hashes.containsKey(inEdge.getSourceId())) {
					return false;
				}
			}

			Hasher hasher = hashFunction.newHasher();
			// 兼顾 node-local properties and input and output edges
			byte[] hash = generateDeterministicHash(node, hasher, hashes, isChainingEnabled, streamGraph);

			if (hashes.put(node.getId(), hash) != null) {
				// Sanity check
				throw new IllegalStateException("Unexpected state. Tried to add node hash " +
						"twice. This is probably a bug in the JobGraph generator.");
			}

			return true;
		} else {
			// 如果用户当前算子设置了uuid
			Hasher hasher = hashFunction.newHasher();
			byte[] hash = generateUserSpecifiedHash(node, hasher);

			for (byte[] previousHash : hashes.values()) {
				if (Arrays.equals(previousHash, hash)) {
					throw new IllegalArgumentException("Hash collision on user-specified ID " +
							"\"" + userSpecifiedHash + "\". " +
							"Most likely cause is a non-unique ID. Please check that all IDs " +
							"specified via `uid(String)` are unique.");
				}
			}

			if (hashes.put(node.getId(), hash) != null) {
				// Sanity check
				throw new IllegalStateException("Unexpected state. Tried to add node hash " +
						"twice. This is probably a bug in the JobGraph generator.");
			}

			return true;
		}
	}

	/**
	 * Generates a hash from a user-specified ID.
	 */
	private byte[] generateUserSpecifiedHash(StreamNode node, Hasher hasher) {
		hasher.putString(node.getTransformationUID(), Charset.forName("UTF-8"));

		return hasher.hash().asBytes();
	}

	/**
	 * Generates a deterministic hash from node-local properties and input and
	 * output edges.
	 */
	private byte[] generateDeterministicHash(
			StreamNode node,
			Hasher hasher,
			Map<Integer, byte[]> hashes,
			boolean isChainingEnabled,
			StreamGraph streamGraph) {

		// Include stream node to hash. We use the current size of the computed
		// hashes as the ID. We cannot use the node's ID, because it is
		// assigned from a static counter. This will result in two identical
		// programs having different hashes.
		generateNodeLocalHash(hasher, hashes.size());

		// Include chained nodes to hash
		for (StreamEdge outEdge : node.getOutEdges()) {
			// 判断能不能和下游节点chain在一起
			if (isChainable(outEdge, isChainingEnabled, streamGraph)) {

				// Use the hash size again, because the nodes are chained to
				// this node. This does not add a hash for the chained nodes.
				generateNodeLocalHash(hasher, hashes.size());
			}
		}

		byte[] hash = hasher.hash().asBytes();

		// Make sure that all input nodes have their hash set before entering
		// this loop (calling this method).
		for (StreamEdge inEdge : node.getInEdges()) {
			byte[] otherHash = hashes.get(inEdge.getSourceId());

			// Sanity check
			if (otherHash == null) {
				throw new IllegalStateException("Missing hash for input node "
						+ streamGraph.getSourceVertex(inEdge) + ". Cannot generate hash for "
						+ node + ".");
			}

			for (int j = 0; j < hash.length; j++) {
				hash[j] = (byte) (hash[j] * 37 ^ otherHash[j]);
			}
		}

		if (LOG.isDebugEnabled()) {
			String udfClassName = "";
			if (node.getOperatorFactory() instanceof UdfStreamOperatorFactory) {
					udfClassName = ((UdfStreamOperatorFactory) node.getOperatorFactory()).getUserFunctionClassName();
			}

			LOG.debug("Generated hash '" + byteToHexString(hash) + "' for node " +
					"'" + node.toString() + "' {id: " + node.getId() + ", " +
					"parallelism: " + node.getParallelism() + ", " +
					"user function: " + udfClassName + "}");
		}

		return hash;
	}

	/**
	 * Applies the {@link Hasher} to the {@link StreamNode} . The hasher encapsulates
	 * the current state of the hash.
	 *
	 * <p>The specified ID is local to this node. We cannot use the
	 * {@link StreamNode#id}, because it is incremented in a static counter.
	 * Therefore, the IDs for identical jobs will otherwise be different.
	 */
	private void generateNodeLocalHash(Hasher hasher, int id) {
		// This resolves conflicts for otherwise identical source nodes. BUT
		// the generated hash codes depend on the ordering of the nodes in the
		// stream graph.
		hasher.putInt(id);
	}

	private boolean isChainable(StreamEdge edge, boolean isChainingEnabled, StreamGraph streamGraph) {
		StreamNode upStreamVertex = streamGraph.getSourceVertex(edge);
		StreamNode downStreamVertex = streamGraph.getTargetVertex(edge);

		StreamOperatorFactory<?> headOperator = upStreamVertex.getOperatorFactory();
		StreamOperatorFactory<?> outOperator = downStreamVertex.getOperatorFactory();

		// chain条件判断
		// 1、下游节点只有一直上游输入
		// 2、两端算子不为空
		// 3、两端算子有相同的slotShareGroup
		// 4、下游算子的chain策略为ALWAYS
		// 5、上游算子的chain策略为HEAD或ALWAYS
		// 6、两节点之前的edge partition为Forward
		// 7、两个算子的并行度相同
		// 8、开起允许Chain配置
		return downStreamVertex.getInEdges().size() == 1
				&& outOperator != null
				&& headOperator != null
				&& upStreamVertex.isSameSlotSharingGroup(downStreamVertex)
				&& outOperator.getChainingStrategy() == ChainingStrategy.ALWAYS
				&& (headOperator.getChainingStrategy() == ChainingStrategy.HEAD ||
				headOperator.getChainingStrategy() == ChainingStrategy.ALWAYS)
				&& (edge.getPartitioner() instanceof ForwardPartitioner)
				&& upStreamVertex.getParallelism() == downStreamVertex.getParallelism()
				&& isChainingEnabled;
	}
}
