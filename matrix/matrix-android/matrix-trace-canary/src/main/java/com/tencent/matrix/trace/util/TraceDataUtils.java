package com.tencent.matrix.trace.util;

import com.tencent.matrix.trace.constants.Constants;
import com.tencent.matrix.trace.core.AppMethodBeat;
import com.tencent.matrix.trace.items.MethodItem;
import com.tencent.matrix.util.MatrixLog;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

public class TraceDataUtils {

    private static final String TAG = "Matrix.TraceDataUtils";

    public interface IStructuredDataFilter {
        boolean isFilter(long during, int filterCount);

        int getFilterMaxCount();

        void fallback(List<MethodItem> stack, int size);
    }

    public static void structuredDataToStack(long[] buffer, LinkedList<MethodItem> result, boolean isStrict, long endTime) {
        long lastInId = 0L;
        int depth = 0;
        LinkedList<Long> rawData = new LinkedList<>();
        boolean isBegin = !isStrict;

        for (long trueId : buffer) {
            if (0 == trueId) {
                continue;
            }
            if (isStrict) {
                if (isIn(trueId) && AppMethodBeat.METHOD_ID_DISPATCH == getMethodId(trueId)) {
                    isBegin = true;
                }

                if (!isBegin) {
                    MatrixLog.d(TAG, "never begin! pass this method[%s]", getMethodId(trueId));
                    continue;
                }

            }
            if (isIn(trueId)) {
                lastInId = getMethodId(trueId);
                if (lastInId == AppMethodBeat.METHOD_ID_DISPATCH) {
                    depth = 0;
                }
                depth++;
                rawData.push(trueId);
            } else {
                int methodId = getMethodId(trueId); // out
                if (!rawData.isEmpty()) {
                    long in = rawData.pop();
                    depth--;
                    while (getMethodId(in) != methodId && !rawData.isEmpty()) {
                        MatrixLog.w(TAG, "[structuredDataToStack] method[%s] not match in! pop[%s] to continue find!", in, methodId);
                        in = rawData.pop();
                        depth--;
                    }
                    if (lastInId == methodId && methodId == AppMethodBeat.METHOD_ID_DISPATCH) {
                        continue;
                    }
                    long outTime = getTime(trueId);
                    long inTime = getTime(in);
                    long during = outTime - inTime;
                    if (during < 0) {
                        MatrixLog.e(TAG, "[structuredDataToStack] trace during invalid:%d", during);
                        rawData.clear();
                        result.clear();
                        return;
                    }
                    MethodItem methodItem = new MethodItem(methodId, (int) during, depth);
                    addMethodItem(result, methodItem);
                } else {
                    MatrixLog.w(TAG, "[structuredDataToStack] method[%s] not found in! ", methodId);
                }
            }
        }

        while (!rawData.isEmpty() && isStrict) {
            long trueId = rawData.pop();
            int methodId = getMethodId(trueId);
            boolean isIn = isIn(trueId);
            long inTime = getTime(trueId) + AppMethodBeat.getDiffTime();
            MatrixLog.w(TAG, "[structuredDataToStack] has never out method[%s], isIn:%s, rawData size:%s", methodId, isIn, rawData.size());
            if (!isIn) {
                MatrixLog.e(TAG, "[structuredDataToStack] why has out Method[%s]? is wrong! ", methodId);
                continue;
            }
            MethodItem methodItem = new MethodItem(methodId, (int) (endTime - inTime), rawData.size());
            addMethodItem(result, methodItem);
        }

        LinkedList<MethodItem> list = new LinkedList<>();
        MethodItem last = null;
        //前后item按depth聚类
        for (MethodItem item : result) {
            if (null != last) {
                int index = list.indexOf(last);
                if (last.depth == item.depth) {
                    list.add(index, item);
                } else {
                    list.add(index + 1, item);
                }

            } else {
                list.add(item);
            }
            last = item;
        }
        result.clear();
        result.addAll(list);

    }

    private static boolean isIn(long trueId) {
        return ((trueId >> 63) & 0x1) == 1;
    }

    private static long getTime(long trueId) {
        return trueId & 0x7FFFFFFFFFFL;
    }

    private static int getMethodId(long trueId) {
        return (int) ((trueId >> 43) & 0xFFFFFL);
    }

    private static int addMethodItem(LinkedList<MethodItem> resultStack, MethodItem item) {
        MethodItem last = null;
        if (!resultStack.isEmpty()) {
            last = resultStack.peek();
        }
        //todo:聚类不够完整，目前看只是将新item和list上一个item比较
        if (null != last && last.methodId == item.methodId && last.depth == item.depth && 0 != item.depth) {
            item.durTime = item.durTime == Constants.DEFAULT_ANR ? last.durTime : item.durTime;
            last.mergeMore(item.durTime);
            return last.durTime;
        } else {
            resultStack.push(item);
            return item.durTime;
        }
    }

    /**
     * Structured the method stack as a tree Data structure
     *
     * @param resultStack
     * @return
     */
    public static int stackToTree(LinkedList<MethodItem> resultStack, TreeNode root) {
        TreeNode lastNode = null;
        ListIterator<MethodItem> iterator = resultStack.listIterator(0);
        int count = 0;
        while (iterator.hasNext()) {
            TreeNode node = new TreeNode(iterator.next(), lastNode);
            count++;
            if (null == lastNode && node.depth() != 0) {
                MatrixLog.e(TAG, "[stackToTree] begin error! why the first node'depth is not 0!");
                return 0;
            }
            int depth = node.depth();
            if (lastNode == null || depth == 0) {
                root.add(node);
            } else if (lastNode.depth() >= depth) {
                while (lastNode.depth() > depth) {
                    lastNode = lastNode.father;
                }
                if (lastNode.father != null) {
                    node.father = lastNode.father;
                    lastNode.father.add(node);
                }
            } else if (lastNode.depth() < depth) {
                lastNode.add(node);
            }
            lastNode = node;
        }
        return count;
    }


    public static long stackToString(LinkedList<MethodItem> stack, StringBuilder reportBuilder, StringBuilder logcatBuilder) {
        logcatBuilder.append("|*   TraceStack:").append("\n");
        logcatBuilder.append("|*        [id count cost]").append("\n");
        Iterator<MethodItem> listIterator = stack.iterator();
        long stackCost = 0; // fix cost
        while (listIterator.hasNext()) {
            MethodItem item = listIterator.next();
            reportBuilder.append(item.toString()).append('\n');
            logcatBuilder.append("|*        ").append(item.print()).append('\n');

            if (stackCost < item.durTime) {
                stackCost = item.durTime;
            }
        }
        return stackCost;
    }


    public static int countTreeNode(TreeNode node) {
        int count = node.children.size();
        Iterator<TreeNode> iterator = node.children.iterator();
        while (iterator.hasNext()) {
            count += countTreeNode(iterator.next());
        }
        return count;
    }

    /**
     * it's the node for the stack tree
     */
    public static final class TreeNode {
        MethodItem item;
        TreeNode father;

        LinkedList<TreeNode> children = new LinkedList<>();

        TreeNode(MethodItem item, TreeNode father) {
            this.item = item;
            this.father = father;
        }

        private int depth() {
            return null == item ? 0 : item.depth;
        }

        private void add(TreeNode node) {
            children.addFirst(node);
        }

        private boolean isLeaf() {
            return children.isEmpty();
        }
    }

    public static void printTree(TreeNode root, StringBuilder print) {
        print.append("|*   TraceStack: ").append("\n");
        printTree(root, 0, print, "|*        ");
    }

    public static void printTree(TreeNode root, int depth, StringBuilder ss, String prefixStr) {

        StringBuilder empty = new StringBuilder(prefixStr);

        for (int i = 0; i <= depth; i++) {
            empty.append("    ");
        }
        for (int i = 0; i < root.children.size(); i++) {
            TreeNode node = root.children.get(i);
            ss.append(empty.toString()).append(node.item.methodId).append("[").append(node.item.durTime).append("]").append("\n");
            if (!node.children.isEmpty()) {
                printTree(node, depth + 1, ss, prefixStr);
            }
        }
    }


    public static void trimStack(List<MethodItem> stack, int targetCount, IStructuredDataFilter filter) {
        if (0 > targetCount) {
            stack.clear();
            return;
        }

        int filterCount = 1;
        int curStackSize = stack.size();
        while (curStackSize > targetCount) {
            ListIterator<MethodItem> iterator = stack.listIterator(stack.size());
            while (iterator.hasPrevious()) {
                MethodItem item = iterator.previous();
                if (filter.isFilter(item.durTime, filterCount)) {
                    iterator.remove();
                    curStackSize--;
                    if (curStackSize <= targetCount) {
                        return;
                    }
                }
            }
            curStackSize = stack.size();
            filterCount++;
            if (filter.getFilterMaxCount() < filterCount) {
                break;
            }
        }
        int size = stack.size();
        if (size > targetCount) {
            filter.fallback(stack, size);
        }
    }

    @Deprecated
    public static String getTreeKey(List<MethodItem> stack, final int targetCount) {
        StringBuilder ss = new StringBuilder();
        final List<MethodItem> tmp = new LinkedList<>(stack);
        trimStack(tmp, targetCount, new TraceDataUtils.IStructuredDataFilter() {
            @Override
            public boolean isFilter(long during, int filterCount) {
                return during < filterCount * Constants.TIME_UPDATE_CYCLE_MS;
            }

            @Override
            public int getFilterMaxCount() {
                return Constants.FILTER_STACK_MAX_COUNT;
            }

            @Override
            public void fallback(List<MethodItem> stack, int size) {
                MatrixLog.w(TAG, "[getTreeKey] size:%s targetSize:%s", size, targetCount);
                Iterator iterator = stack.listIterator(Math.min(size, targetCount));
                while (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        });
        for (MethodItem item : tmp) {
            ss.append(item.methodId + "|");
        }
        return ss.toString();
    }

    public static String getTreeKey(List<MethodItem> stack, long stackCost) {
        StringBuilder ss = new StringBuilder();
        long allLimit = (long) (stackCost * Constants.FILTER_STACK_KEY_ALL_PERCENT);

        List<MethodItem> sortList = new LinkedList<>();

        for (MethodItem item : stack) {
            if (item.durTime >= allLimit) {
                sortList.add(item);
            }
        }

        Collections.sort(sortList, new Comparator<MethodItem>() {
            @Override
            public int compare(MethodItem o1, MethodItem o2) {
                return Long.compare((o2.depth + 1) * o2.durTime, (o1.depth + 1) * o1.durTime);
            }
        });

        if (sortList.isEmpty() && !stack.isEmpty()) {
            sortList.add(stack.get(0));
        } else if (!sortList.isEmpty()) {
            sortList = sortList.subList(0, 1);
        }

        for (MethodItem item : sortList) {
            ss.append(item.methodId + "|");
        }
        return ss.toString();
    }


}
