package eu.vnagy.argotools.junit.testutil;

/*-
 * #%L
 * Argo JUnit
 * %%
 * Copyright (C) 2026 Vilmos Szabó-Nagy
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * Creates a step-container factory that routes containers through a Docker-in-Docker (DinD) daemon.
 *
 * <p>The returned factory:
 * <ol>
 *   <li>Overrides {@code GenericContainer}'s {@code protected dockerClient} field with the DinD client.</li>
 *   <li>Wraps that client so that Testcontainers' {@code connectToPortForwardingNetwork()} becomes a
 *       silent no-op: the method passes a host-daemon network ID to the remote daemon, which doesn't
 *       know it. Step containers don't expose ports, so skipping this setup is safe.</li>
 * </ol>
 */
public final class DindContainerFactory {

    private DindContainerFactory() {}

    /**
     * Returns a factory that creates step containers pinned to {@code dindClient}.
     *
     * @param dindClient a docker-java client connected to the DinD daemon
     */
    public static Function<DockerImageName, GenericContainer<?>> forClient(DockerClient dindClient) {
        DockerClient tolerant = portForwardingTolerant(dindClient);
        return image -> new DindContainer(image, tolerant);
    }

    private static final class DindContainer extends GenericContainer<DindContainer> {
        DindContainer(DockerImageName image, DockerClient client) {
            super(image);
            this.dockerClient = client; // protected field in GenericContainer
        }
    }

    /**
     * Wraps {@code real} so that {@code inspectNetworkCmd} and {@code connectToNetworkCmd} swallow
     * {@link NotFoundException}. This prevents {@code GenericContainer.connectToPortForwardingNetwork()}
     * from crashing when it passes a host-daemon network ID to the remote daemon.
     */
    private static DockerClient portForwardingTolerant(DockerClient real) {
        return (DockerClient) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class<?>[]{DockerClient.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(real, args);
                        String name = method.getName();
                        if ("inspectNetworkCmd".equals(name) || "connectToNetworkCmd".equals(name)) {
                            return silentOnNotFound(result, method.getReturnType());
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * Wraps {@code cmd} (an {@code InspectNetworkCmd} or {@code ConnectToNetworkCmd}) so that
     * {@code exec()} catches {@link NotFoundException} instead of propagating it. Builder methods
     * that return the same object are redirected back to the proxy to keep the chain intercepted.
     */
    private static Object silentOnNotFound(Object cmd, Class<?> iface) {
        return Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if ("exec".equals(method.getName())) {
                        try {
                            return method.invoke(cmd, args);
                        } catch (InvocationTargetException e) {
                            if (e.getCause() instanceof NotFoundException) return null;
                            throw e.getCause();
                        }
                    }
                    try {
                        Object r = method.invoke(cmd, args);
                        return (r == cmd) ? proxy : r; // keep builder chain on the proxy
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
