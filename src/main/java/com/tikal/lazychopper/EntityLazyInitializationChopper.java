package com.tikal.lazychopper;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Hibernate;
import org.hibernate.collection.PersistentCollection;
import org.hibernate.envers.entities.mapper.relation.lazy.proxy.CollectionProxy;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;

import com.tikal.lazychopper.GraphTraverser.TraverseException;

public class EntityLazyInitializationChopper implements LazyInitializationChopper {

	// do we need to use concurrent map here
	private Map<Class<?>, List<Field>> immutableMap;
	private GraphTraverser graphTraverser = new GraphTraverser();

	private static final String RESOURCE_PATTERN = "/**/*.class";
	private ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

	private String modelpackage;

	private Class<?> abstractEntityClass;

	public void setModelpackage(String modelpackage) {
		this.modelpackage = modelpackage;
	}

	public void setAbstractEntityClass(Class<?> abstractEntityClass) {
		this.abstractEntityClass = abstractEntityClass;
	}

	public void init() throws IOException, ClassNotFoundException {
		Map<Class<?>, List<Field>> classFieldsMap = new HashMap<Class<?>, List<Field>>();
		String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
				+ ClassUtils.convertClassNameToResourcePath(modelpackage) + RESOURCE_PATTERN;
		Resource[] resources = resourcePatternResolver.getResources(pattern);
		MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(this.resourcePatternResolver);
		for (Resource resource : resources) {
			if (resource.isReadable()) {
				MetadataReader reader = readerFactory.getMetadataReader(resource);
				String className = reader.getClassMetadata().getClassName();
				Class<?> clazz = getClass().getClassLoader().loadClass(className);

				if (clazz.isAnnotationPresent(Chopped.class) || abstractEntityClass.isAssignableFrom(clazz)
						|| clazz.getAnnotation(javax.persistence.Embeddable.class) != null) {
					List<Field> fields = new ArrayList<Field>();
					getAllFields(classFieldsMap, clazz, fields);
					classFieldsMap.put(clazz, fields);
				}
			}
		}

		immutableMap = Collections.unmodifiableMap(classFieldsMap);
	}

	/*
	 * method that run over all nodes and clear their unloaded children. It uses
	 * TraverseGraph to traverse all nodes.
	 * 
	 * The API include
	 * 
	 * @Object node : root
	 */
	/*
	 * (non-Javadoc)
	 * 
	 * @see com.gilat.ngnms.server.persistence.jpa.infra.hibernate.
	 * LazyInitializationChopperInterface#chop(java.lang.Object)
	 */
	@Override
	public Object chop(Object node) throws LazyInitializationChopperException {
		try {
			graphTraverser.traverse(node, new GraphTraverser.GraphNodeVisitor() {

				private Set<Object> visited = new HashSet<Object>();

				@Override
				public Set<Object> visitAndGetDescendants(Object node) throws TraverseException {
					visited.add(node);
					try {
						return chopLazyAndReturnDescendants(node);
					} catch (Exception e) {
						throw new GraphTraverser.TraverseException(e);
					}
				}

				@Override
				public boolean isUnvisited(Object node) {
					return !visited.contains(node);
				}

			});
			return node;
		} catch (TraverseException cause) {
			throw new LazyInitializationChopperException(cause);
		}
	}

	private Set<Object> chopLazyAndReturnDescendants(Object node) throws IllegalAccessException,
																	IllegalArgumentException, SecurityException,
																	NoSuchFieldException {

		Set<Object> descendants = new HashSet<Object>();

		List<Field> fields = immutableMap.get(node.getClass());
		if (fields != null) {
			for (Field field : fields) {
				chopLazyField(node, descendants, field);
			}
		}

		return descendants;
	}

	private void getAllFields(Map<Class<?>, List<Field>> map, Class<?> nodeClass, List<Field> fields) {
		if (map.containsKey(nodeClass)) {
			fields.addAll(map.get(nodeClass));
			return;
		}

		if (nodeClass == null) {
			return;
		}

		for (Field fild : nodeClass.getDeclaredFields()) {
			fields.add(fild);
		}

		getAllFields(map, nodeClass.getSuperclass(), fields);
	}

	private void chopLazyField(Object node, Set<Object> descendents, Field field) throws IllegalAccessException,
																					IllegalArgumentException,
																					SecurityException,
																					NoSuchFieldException {
		field.setAccessible(true);
		Object fieldVal = field.get(node);
		if (fieldVal == null) {
			return;
		}
		if (fieldVal instanceof CollectionProxy) {
			CollectionProxy collectionProxy = (CollectionProxy) fieldVal;
			Field delegateField = CollectionProxy.class.getDeclaredField("delegate");
			delegateField.setAccessible(true);
			if (delegateField.get(collectionProxy) == null) {
				fieldVal = null;
			} else {
				handleIntitialized(descendents, fieldVal, node, field);
			}
		} else if (Hibernate.isInitialized(fieldVal)) {
			handleIntitialized(descendents, fieldVal, node, field);
		} else if (fieldVal instanceof HibernateProxy) {
			handleUnIntilizedProxy(node, field, fieldVal);
		} else {
			field.set(node, null);// uninitilzed Collection or Map
		}
	}

	private void handleUnIntilizedProxy(Object node, Field field, Object fieldVal) throws IllegalAccessException {
		Object lazyReplacement = cloneProxy(fieldVal);
		field.set(node, lazyReplacement);
	}

	private void handleIntitialized(Set<Object> descendents, Object fieldVal, Object node, Field field)
																										throws SecurityException,
																										NoSuchFieldException,
																										IllegalArgumentException,
																									IllegalAccessException {
		if(fieldVal instanceof Collection && collectionContainsProxies((Collection)fieldVal)){
			fieldVal = filterProxiesFromCollection((Collection)fieldVal);
			field.set(node, fieldVal);
		}
		if (fieldVal instanceof PersistentCollection) {
			descendents.addAll((Collection<?>) ((PersistentCollection) fieldVal).getValue());
		} else if (fieldVal instanceof HibernateProxy) {
			Object implementation = ((HibernateProxy) fieldVal).getHibernateLazyInitializer().getImplementation();
			field.set(node, implementation);
			descendents.add(implementation);
		} else if (fieldVal instanceof CollectionProxy) {
			CollectionProxy collectionProxy = (CollectionProxy) fieldVal;
			Field delegateField = CollectionProxy.class.getDeclaredField("delegate");
			delegateField.setAccessible(true);
			Object implementation = delegateField.get(collectionProxy);
			field.set(node, implementation);
			descendents.addAll(((Collection) implementation));
		} else if (fieldVal != null
				&& (abstractEntityClass.isAssignableFrom(fieldVal.getClass()) || fieldVal.getClass()
						.isAnnotationPresent(Chopped.class))) {
			descendents.add(fieldVal);
		} else if (fieldVal instanceof Collection) {
			descendents.addAll((Collection<Object>) fieldVal);
		}
	}

	@Override
	public Collection<?> filterProxiesFromCollection(Collection collection) {
		Collection newCol =  createCollection(collection);
		for (Object node : collection) 
			if (node instanceof HibernateProxy){
				HibernateProxy proxy = (HibernateProxy) node;
				if(Hibernate.isInitialized(proxy)){//initilized proxy
					newCol.add(proxy.getHibernateLazyInitializer().getImplementation());
				}
			} else{//Its regular object
				newCol.add(node);
			}
		return newCol;
	}

	private Collection createCollection(Collection<?> collection) {
		if (collection instanceof Set) 
			return new LinkedHashSet();
		return new LinkedList();		
	}

	@Override
	public boolean collectionContainsProxies(Collection collection) {
		for (Object node : collection) 
			if (node instanceof HibernateProxy)
				return true;
		return false;
	}

	@SuppressWarnings("rawtypes")
	private Object cloneProxy(Object fieldVal) throws SecurityException {
		HibernateProxy hpo = (HibernateProxy) fieldVal;
		LazyInitializer lazyInitializer = hpo.getHibernateLazyInitializer();
		Class<?> clazz = lazyInitializer.getPersistentClass();
		Object identifier = lazyInitializer.getIdentifier();

		Constructor[] ctors = clazz.getDeclaredConstructors();
		Constructor ctor = null;
		for (int i = 0; i < ctors.length; i++) {
			if (ctors[i].getGenericParameterTypes().length == 0) {
				ctor = ctors[i];
				break;
			}
		}

		if (ctor == null) {
			throw new RuntimeException("no default constructor");
		}
		try {
			ctor.setAccessible(true);
			Object lazyReplacement;
			lazyReplacement = ctor.newInstance();
			Field idField = abstractEntityClass.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(lazyReplacement, identifier);
			return lazyReplacement;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}