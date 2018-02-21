package com.ctrip.framework.apollo.spring.annotation;

import com.ctrip.framework.apollo.ConfigChangeListener;
import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.model.ConfigChange;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.config.ConfigPropertySource;
import com.ctrip.framework.apollo.spring.property.PlaceholderHelper;
import com.ctrip.framework.apollo.spring.property.SpringValue;
import com.ctrip.framework.apollo.spring.property.SpringValueDefinition;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.google.common.base.Strings;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.SystemPropertyUtils;

/**
 * Spring value processor of field or method which has @Value.
 *
 * @author github.com/zhegexiaohuozi  seimimaster@gmail.com
 * @since 2017/12/20.
 */
public class SpringValueProcessor implements BeanPostProcessor, PriorityOrdered, EnvironmentAware,
    BeanFactoryAware, BeanDefinitionRegistryPostProcessor {

  private static final Logger logger = LoggerFactory.getLogger(SpringValueProcessor.class);

  private final Multimap<String, SpringValue> monitor = LinkedListMultimap.create();
  private final Multimap<String, SpringValueDefinition> beanName2SpringValueDefinitions =
      LinkedListMultimap.create();
  private final AtomicBoolean configChangeListenerRegistered = new AtomicBoolean(false);
  private final boolean autoUpdateInjectedSpringProperties;
  private final PlaceholderHelper placeholderHelper;

  private ConfigurableEnvironment environment;
  private ConfigurableBeanFactory beanFactory;
  private TypeConverter typeConverter;

  public SpringValueProcessor() {
    autoUpdateInjectedSpringProperties = ApolloInjector.getInstance(ConfigUtil.class)
        .isAutoUpdateInjectedSpringProperties();
    placeholderHelper = ApolloInjector.getInstance(PlaceholderHelper.class);
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
    this.beanFactory = (ConfigurableBeanFactory) beanFactory;
    this.typeConverter = this.beanFactory.getTypeConverter();
  }

  @Override
  public void setEnvironment(Environment env) {
    this.environment = (ConfigurableEnvironment) env;
  }

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
      throws BeansException {
    if (autoUpdateInjectedSpringProperties) {
      processPropertyValues(registry);
    }
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
      throws BeansException {
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName)
      throws BeansException {
    if (autoUpdateInjectedSpringProperties) {
      if (configChangeListenerRegistered.compareAndSet(false, true)) {
        registerConfigChangeListener();
      }
      Class clazz = bean.getClass();
      processFields(bean, findAllField(clazz));
      processMethods(bean, findAllMethod(clazz));
      processBeanPropertyValues(bean, beanName);
    }
    return bean;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    return bean;
  }

  private void processPropertyValues(BeanDefinitionRegistry beanRegistry) {
    String[] beanNames = beanRegistry.getBeanDefinitionNames();
    for (String beanName : beanNames) {
      BeanDefinition beanDefinition = beanRegistry.getBeanDefinition(beanName);
      MutablePropertyValues mutablePropertyValues = beanDefinition.getPropertyValues();
      List<PropertyValue> propertyValues = mutablePropertyValues.getPropertyValueList();
      for (PropertyValue propertyValue : propertyValues) {
        Object value = propertyValue.getValue();
        if (!(value instanceof TypedStringValue)) {
          continue;
        }
        String placeholder = ((TypedStringValue) value).getValue();
        Set<String> keys = placeholderHelper.extractPlaceholderKeys(placeholder);

        if (keys.isEmpty()) {
          continue;
        }

        for (String key : keys) {
          beanName2SpringValueDefinitions
              .put(beanName, new SpringValueDefinition(key, placeholder, propertyValue.getName()));
        }
      }
    }
  }

  private void processFields(Object bean, List<Field> declaredFields) {
    for (Field field : declaredFields) {
      // register @Value on field
      Value value = field.getAnnotation(Value.class);
      if (value == null) {
        continue;
      }
      Set<String> keys = placeholderHelper.extractPlaceholderKeys(value.value());

      if (keys.isEmpty()) {
        continue;
      }

      for (String key : keys) {
        SpringValue springValue = new SpringValue(key, value.value(), bean, field);
        monitor.put(key, springValue);
        logger.debug("Monitoring {}", springValue);
      }
    }
  }

  private void processMethods(final Object bean, List<Method> declaredMethods) {
    for (final Method method : declaredMethods) {
      //register @Value on method
      Value value = method.getAnnotation(Value.class);
      if (value == null) {
        continue;
      }
      //skip Configuration bean methods
      if (method.getAnnotation(Bean.class) != null) {
        continue;
      }
      if (method.getParameterTypes().length != 1) {
        logger.error("Ignore @Value setter {}.{}, expecting 1 parameter, actual {} parameters",
            bean.getClass().getName(), method.getName(), method.getParameterTypes().length);
        continue;
      }

      Set<String> keys = placeholderHelper.extractPlaceholderKeys(value.value());

      if (keys.isEmpty()) {
        continue;
      }

      for (String key : keys) {
        SpringValue springValue = new SpringValue(key, value.value(), bean, method);
        monitor.put(key, springValue);
        logger.debug("Monitoring {}", springValue);
      }
    }
  }

  private void processBeanPropertyValues(Object bean, String beanName) {
    Collection<SpringValueDefinition> propertySpringValues = beanName2SpringValueDefinitions
        .get(beanName);
    if (propertySpringValues == null || propertySpringValues.isEmpty()) {
      return;
    }

    for (SpringValueDefinition definition : propertySpringValues) {
      try {
        PropertyDescriptor pd = BeanUtils
            .getPropertyDescriptor(bean.getClass(), definition.getPropertyName());
        Method method = pd.getWriteMethod();
        if (method == null) {
          continue;
        }
        SpringValue springValue = new SpringValue(definition.getKey(), definition.getPlaceholder(),
            bean, method);
        monitor.put(definition.getKey(), springValue);
        logger.debug("Monitoring {}", springValue);
      } catch (Throwable ex) {
        logger.error("Failed to enable auto update feature for {}.{}", bean.getClass(),
            definition.getPropertyName());
      }
    }

    // clear
    beanName2SpringValueDefinitions.removeAll(beanName);
  }

  private List<Field> findAllField(Class clazz) {
    final List<Field> res = new LinkedList<>();
    ReflectionUtils.doWithFields(clazz, new ReflectionUtils.FieldCallback() {
      @Override
      public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
        res.add(field);
      }
    });
    return res;
  }

  private List<Method> findAllMethod(Class clazz) {
    final List<Method> res = new LinkedList<>();
    ReflectionUtils.doWithMethods(clazz, new ReflectionUtils.MethodCallback() {
      @Override
      public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
        res.add(method);
      }
    });
    return res;
  }

  private void registerConfigChangeListener() {
    ConfigChangeListener changeListener = new ConfigChangeListener() {
      @Override
      public void onChange(ConfigChangeEvent changeEvent) {
        Set<String> keys = changeEvent.changedKeys();
        if (CollectionUtils.isEmpty(keys)) {
          return;
        }
        for (String key : keys) {
          // 1. check whether the changed key is relevant
          Collection<SpringValue> targetValues = monitor.get(key);
          if (targetValues == null || targetValues.isEmpty()) {
            continue;
          }

          // 2. check whether the value is really changed or not (since spring property sources have hierarchies)
          ConfigChange configChange = changeEvent.getChange(key);
          if (!Objects.equals(environment.getProperty(key), configChange.getNewValue())) {
            continue;
          }

          // 3. update the value
          for (SpringValue val : targetValues) {
            updateSpringValue(val);
          }
        }
      }
    };

    for (PropertySource<?> propertySource : environment.getPropertySources()) {
      if (!(propertySource instanceof CompositePropertySource)) {
        continue;
      }
      Collection<PropertySource<?>> compositePropertySources = ((CompositePropertySource) propertySource)
          .getPropertySources();
      for (PropertySource<?> compositePropertySource : compositePropertySources) {
        if (compositePropertySource instanceof ConfigPropertySource) {
          ((ConfigPropertySource) compositePropertySource).addChangeListener(changeListener);
        }
      }
    }
  }

  private void updateSpringValue(SpringValue springValue) {
    try {
      String strVal = beanFactory.resolveEmbeddedValue(springValue.getPlaceholder());
      Object value;

      if (springValue.isField()) {
        value = this.typeConverter
            .convertIfNecessary(strVal, springValue.getTargetType(), springValue.getField());
      } else {
        value = this.typeConverter.convertIfNecessary(strVal, springValue.getTargetType(),
            springValue.getMethodParameter());
      }

      springValue.update(value);

      logger.debug("Auto update apollo changed value successfully, new value: {}, {}", strVal,
          springValue.toString());
    } catch (Throwable ex) {
      logger.error("Auto update apollo changed value failed, {}", springValue.toString(), ex);
    }
  }

  @Override
  public int getOrder() {
    //make it as late as possible
    return Ordered.LOWEST_PRECEDENCE;
  }
}
