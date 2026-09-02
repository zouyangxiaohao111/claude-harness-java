package com.nexusai.infra.config;

import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.stereotype.Component;

/**
 * Workaround for MyBatis-Flex 1.10.0 + Spring Boot 3.5 native-image AOT incompatibility.
 *
 * <p><b>Why this exists</b>: MyBatis-Flex has no AOT support. When Spring Boot 3.5's AOT
 * engine generates bean definitions for each {@code @Mapper}-scanned interface, it
 * produces
 * <pre>{@code
 * BeanInstanceSupplier.<MapperFactoryBean>forConstructor(Class.class)
 *     .withGenerator((registeredBean, args) -> new MapperFactoryBean(args.get(0)));
 * }</pre>
 * which resolves the {@code Class<?>} constructor argument by autowire-by-type at
 * runtime. Without any {@code Class<?>} beans registered, every mapper fails with
 * "No qualifying bean of type 'java.lang.Class<?>'".
 *
 * <p><b>This fix</b>: a {@link BeanDefinitionRegistryPostProcessor} runs at native-image
 * startup, scans all registered mapper beans, and rewrites each definition to
 * pass the exact {@code Class} (e.g. {@code ProviderMapper.class}) as the explicit
 * constructor argument. Bypasses autowire resolution entirely.
 *
 * <p>Remove this class once MyBatis-Flex ships native-image support.
 */
@Component
public class MapperClassRegistrationConfig implements BeanDefinitionRegistryPostProcessor {

    private static final String MAPPER_BEAN_NAME_SUFFIX = "Mapper";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        for (String name : registry.getBeanDefinitionNames()) {
            if (!isMapperBeanName(name)) {
                continue;
            }
            BeanDefinition bd = registry.getBeanDefinition(name);
            if (!(bd instanceof RootBeanDefinition root)) {
                continue;
            }

            // Resolve the Class<?> the mapper factory needs.
            Object mapperInterface = root.getPropertyValues().get("mapperInterface");
            Class<?> ifaceClass = (mapperInterface instanceof Class<?> c) ? c : null;
            if (ifaceClass == null) {
                continue;
            }

            // Always rebuild: AOT's generated BeanInstanceSupplier resolves the
            // Class<?> arg via autowire-by-type, which fails in native-image.
            // Replace with a deterministic direct-arg constructor.
            RootBeanDefinition override = new RootBeanDefinition(MapperFactoryBean.class);
            override.setTargetType(root.getTargetType());
            override.setLazyInit(root.isLazyInit());
            override.setScope(root.getScope());
            override.getConstructorArgumentValues().addIndexedArgumentValue(0, ifaceClass);
            override.getPropertyValues().add("mapperInterface", ifaceClass);
            override.getPropertyValues().add("addToConfig", true);

            registry.registerBeanDefinition(name, override);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        // No-op: all work done in postProcessBeanDefinitionRegistry.
    }

    private static boolean isMapperBeanName(String name) {
        return name.endsWith(MAPPER_BEAN_NAME_SUFFIX)
                && Character.isLowerCase(name.charAt(0))
                && !name.equals("mapperScannerConfigurer");
    }
}
