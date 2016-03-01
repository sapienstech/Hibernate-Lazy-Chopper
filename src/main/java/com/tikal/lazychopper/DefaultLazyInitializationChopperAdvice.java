package com.tikal.lazychopper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.envers.internal.entities.mapper.relation.lazy.proxy.CollectionProxy;
import org.hibernate.proxy.HibernateProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Stack;

/*
 * assume that each time the service returns an object
 * the chopper need to chop the relevant nodes.
 */
public class DefaultLazyInitializationChopperAdvice implements LazyInitializationChopperAdvice {
	private static final Logger logger = LoggerFactory.getLogger(DefaultLazyInitializationChopperAdvice.class);

	private static ThreadLocal<Boolean> disableChop = new ThreadLocal<Boolean>();
	
	private LazyInitializationChopper lazyInitializationChopper;
	// private LazyInitializationChopper collectionLazyInitializationChopper;
	private Class<?> abstractEntityClass;
	// private LazyInitializationChopper resultPageLazyInitializationChopper;
	private int order;

	// It must be static since its common to all Chopper advices classes
	public static ThreadLocal<Stack<Object>> chopStackHolder = new ThreadLocal<Stack<Object>>();

	public void setLazyInitializationChopper(LazyInitializationChopper lazyInitializationChopper) {
		this.lazyInitializationChopper = lazyInitializationChopper;
	}

	// public void
	// setCollectionLazyInitializationChopper(LazyInitializationChopper
	// collectionLazyInitializationChopper) {
	// this.collectionLazyInitializationChopper =
	// collectionLazyInitializationChopper;
	// }

	// public void
	// setResultPageLazyInitializationChopper(LazyInitializationChopper
	// resultPageLazyInitializationChopper) {
	// this.resultPageLazyInitializationChopper =
	// resultPageLazyInitializationChopper;
	// }

	public void setAbstractEntityClass(Class<?> abstractEntityClass) {
		this.abstractEntityClass = abstractEntityClass;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public void setOrder(int order) {
		this.order = order;
	}
	
	public void disableChop(){
		disableChop.set(true);
	}
	
	public void enableChop(){
		disableChop.set(false);
	}

	@Override
	public Object chop(ProceedingJoinPoint call) throws Throwable {
		Object retVal;
		try {
			pushChopToStack();
			retVal = call.proceed();
		} finally {
			popChopFromStack();
		}

		// chop the return value
		if (isChopStackEmpty() && (disableChop==null || disableChop.get()==null || disableChop.get()==false)) {
			retVal = handleIntitializedRoot(retVal);
			// doChop(call, retVal);
		}

		// need to check what are we doing with the exception.

		return retVal;
	}

	private Object handleIntitializedRoot(Object node) throws LazyInitializationChopperException, SecurityException,
														NoSuchFieldException, IllegalArgumentException,
														IllegalAccessException {
		if (node == null) {
			return null;
		}
		if (node instanceof HibernateProxy) {
			node = ((HibernateProxy) node).getHibernateLazyInitializer().getImplementation();
		}
		if ((abstractEntityClass.isAssignableFrom(node.getClass()) || node.getClass()
				.isAnnotationPresent(Chopped.class))) {
			return lazyInitializationChopper.chop(node);
		}

		if (node instanceof Section) {
			for (Object collectionNode : ((Section<?>) node).getResults()) {
				handleIntitializedRoot(collectionNode);
			}
		} else {
			node = chopCollection(node);
		}

		return node;

	}

	private Object chopCollection(Object node) throws NoSuchFieldException, IllegalAccessException,
												LazyInitializationChopperException {
		if (node instanceof PersistentCollection) {
			node = ((PersistentCollection) node).getValue();
		}
		if (node instanceof CollectionProxy) {
			Field delegateField = CollectionProxy.class.getDeclaredField("delegate");
			delegateField.setAccessible(true);
			node = (delegateField.get(node));
		}
		Object filteredNode=node;
		if (node instanceof Collection) {
			if(lazyInitializationChopper.collectionContainsProxies((Collection)node))
				filteredNode = lazyInitializationChopper.filterProxiesFromCollection((Collection)node);
			for (Object collectionNode : ((Collection)filteredNode))
				handleIntitializedRoot(collectionNode);
		}
		return filteredNode;
	}

//	private Collection<?> filterProxiesFromCollection(Collection<?> collection) {
//		Collection newCol =  createCollection(collection);
//		for (Object node : collection) 
//			if (node instanceof HibernateProxy){
//				HibernateProxy proxy = (HibernateProxy) node;
//				if(Hibernate.isInitialized(proxy)){//initilized proxy
//					newCol.add(proxy.getHibernateLazyInitializer().getImplementation());
//				}
//			} else{//Its regular object
//				newCol.add(node);
//			}
//		return newCol;
//	}
//
//	private Collection createCollection(Collection<?> collection) {
//		if (collection instanceof Set) 
//			return new LinkedHashSet();
//		return new LinkedList();		
//	}
//
//	private boolean collectionContainsProxies(Collection<?> collection) {
//		for (Object node : collection) 
//			if (node instanceof HibernateProxy)
//				return true;
//		return false;
//	}
	
	//
	// private Object doChop(Object node){
	// //create recursive to handle collections of collections
	// if (node instanceof Collection)
	// for (Object collectionNode : ((Collection<?>)node))
	// doChop(collectionNode);
	// else
	// lazyInitializationChopper.chop(node);
	// }

	// private void doChop(ProceedingJoinPoint call, Object retVal) throws
	// LazyInitializationChopperException {
	// if (retVal!=null &&
	// (abstractEntityClass.isAssignableFrom(retVal.getClass()) ||
	// retVal.getClass().isAnnotationPresent(Chopped.class)))
	// lazyInitializationChopper.chop(retVal);
	// else if (retVal instanceof Collection<?>)
	// collectionLazyInitializationChopper.chop(retVal);
	// // else if (retVal instanceof Section<?>)
	// // resultPageLazyInitializationChopper.chop(retVal);
	//
	// if (logger.isTraceEnabled())
	// logger.trace("Chopped " + call.getSignature().getName());
	// }

	

	private boolean isChopStackEmpty() {
		return getStackOnThread().isEmpty();
	}

	private void popChopFromStack() {
		getStackOnThread().pop();
	}

	private void pushChopToStack() {
		getStackOnThread().push(new Object());
	}

	private Stack<Object> getStackOnThread() {
		Stack<Object> stack = chopStackHolder.get();
		if (stack == null) {
			stack = new Stack<Object>();
			chopStackHolder.set(stack);
		}
		return stack;
	}

}
