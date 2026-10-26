package dev.nvkbridge.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.LoggerAdapterConsole;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinServiceBootstrap;
import org.spongepowered.asm.service.IPropertyKey;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.util.IConsumer;
import org.spongepowered.asm.util.ReEntranceLock;

/** Applies the real Mixin transformer to client bytes without defining the client class. */
@SuppressWarnings("deprecation")
public final class MixinTransformationVerifier {
    private static final String CLIENT_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87";
    private static final String TARGET = "com/mojang/blaze3d/platform/NativeLibrariesBootstrap";
    private static final String BRIDGE = "dev/nvkbridge/runtime/NvkBridge";
    private static Path clientJar;

    private MixinTransformationVerifier() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass the official Minecraft 26.3 client JAR.");
        clientJar = Path.of(args[0]).toRealPath();
        byte[] clientBytes = Files.readAllBytes(clientJar);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(clientBytes));
        require(CLIENT_SHA1.equals(digest), "The client is not the pinned official Minecraft 26.3 artifact.");
        System.setProperty("mixin.bootstrapService", "dev.nvkbridge.runtime.MixinTransformationVerifier$BootstrapService");
        System.setProperty("mixin.service", "dev.nvkbridge.runtime.MixinTransformationVerifier$OfflineService");
        MixinBootstrap.init();
        require(MixinService.getService() instanceof OfflineService, "The offline Mixin service was not selected.");
        OfflineService service = (OfflineService) MixinService.getService();
        service.advancePhase(MixinEnvironment.Phase.DEFAULT);
        MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("nvkbridge.mixins.json");
        require(service.factory != null, "Mixin did not offer its transformer factory to the offline service.");
        IMixinTransformer transformer = service.factory.createTransformer();
        String dottedName = TARGET.replace('/', '.');
        byte[] original = readClientResource(TARGET + ".class");
        require(original != null, "The native bootstrap class is missing from the client.");
        byte[] transformed = transformer.transformClass(environment, dottedName, original);
        ClassNode target = new ClassNode();
        new ClassReader(transformed).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode probe = findMethod(target, "tryLoadingVulkan", "()Z");
        MethodNode configure = findMergedHandler(target, "configureBeforeVulkanCreate");
        MethodNode verify = findMergedHandler(target, "verifyLoadedProvider");
        MethodInsnNode configurationCall = findCall(probe, TARGET, configure.name, configure.desc);
        MethodInsnNode creationCall = findCall(probe, "org/lwjgl/vulkan/VK", "create", "()V");
        MethodInsnNode verificationCall = findCall(probe, TARGET, verify.name, verify.desc);
        int before = probe.instructions.indexOf(configurationCall);
        int creation = probe.instructions.indexOf(creationCall);
        int after = probe.instructions.indexOf(verificationCall);
        require(before < creation && creation < after, "The transformed handlers do not surround VK.create().");
        boolean sameHandler = false;
        for (TryCatchBlockNode block : probe.tryCatchBlocks) {
            int start = probe.instructions.indexOf(block.start);
            int end = probe.instructions.indexOf(block.end);
            if ("java/lang/Throwable".equals(block.type) && start < before && after < end) {
                sameHandler = true;
            }
        }
        require(sameHandler, "The injected calls must remain covered by the same vanilla Throwable handler.");
        require(target.version == Opcodes.V25, "The transformed client class must remain Java 25 bytecode.");
        require(MixinEnvironment.getCompatibilityLevel().getClassMajorVersion() >= Opcodes.V25,
            "The selected Mixin runtime does not support the configured JAVA_25 level.");
        new Analyzer<>(new BasicVerifier()).analyze(TARGET, probe);
        new Analyzer<>(new BasicVerifier()).analyze(TARGET, configure);
        new Analyzer<>(new BasicVerifier()).analyze(TARGET, verify);
        verifyWarningTransformation(transformer, environment);
        require(service.targetClassLoads == 0, "The offline service attempted to define or initialize a client/native class.");
        System.out.println("PASS: real Mixin transformation applied nvkbridge.mixins.json to the pinned Minecraft 26.3 class.");
        System.out.println("PASS: configuration and verification calls surround VK.create inside the original Throwable handler.");
        System.out.println("PASS: transformed probe and both merged handlers pass offline ASM stack analysis.");
        System.out.println("PASS: JAVA_25 accepted by "
            + MixinBootstrap.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("No Minecraft class was defined or executed. Actual Fabric/NeoForge startup and macOS GPU behavior remain untested.");
    }

    private static MethodNode findMethod(ClassNode type, String name, String descriptor) {
        List<MethodNode> matches = type.methods.stream()
            .filter(method -> name.equals(method.name) && descriptor.equals(method.desc)).toList();
        require(matches.size() == 1, "Expected exactly one method " + name + descriptor);
        return matches.getFirst();
    }

    private static void verifyWarningTransformation(IMixinTransformer transformer, MixinEnvironment environment) throws Exception {
        String owner = "net/minecraft/client/Minecraft";
        byte[] original = readClientResource(owner + ".class");
        require(original != null, "The Minecraft class is missing from the client.");
        byte[] bytes = transformer.transformClass(environment, owner.replace('/', '.'), original);
        ClassNode transformed = new ClassNode();
        new ClassReader(bytes).accept(transformed, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String warning = "dev/nvkbridge/runtime/OpenGlWarning";
        List<MethodNode> handlers = transformed.methods.stream().filter(method -> {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && warning.equals(call.owner)
                    && "onGameLoadFinished".equals(call.name) && "(Ljava/lang/Object;)V".equals(call.desc)) return true;
            }
            return false;
        }).toList();
        require(handlers.size() == 1, "Expected one merged graphics-warning handler.");
        MethodNode handler = handlers.getFirst();
        require((handler.access & Opcodes.ACC_STATIC) == 0, "The graphics warning must receive the actual client instance.");
        MethodNode loaded = findMethod(transformed, "onGameLoadFinished", "(Lnet/minecraft/client/GameLoadCookie;)V");
        int calls = 0;
        for (AbstractInsnNode instruction : loaded.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                && handler.name.equals(call.name) && handler.desc.equals(call.desc)) calls++;
        }
        require(calls == 1, "The graphics warning must be injected once at the startup completion return.");
        new Analyzer<>(new BasicVerifier()).analyze(owner, handler);
        new Analyzer<>(new BasicVerifier()).analyze(owner, loaded);
        System.out.println("PASS: graphics warning Mixin applies to the real startup completion hook; stack analysis passes.");
    }

    private static MethodNode findMergedHandler(ClassNode type, String bridgeMethod) {
        List<MethodNode> matches = type.methods.stream().filter(method -> {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && BRIDGE.equals(call.owner)
                    && bridgeMethod.equals(call.name) && "()V".equals(call.desc)) {
                    return true;
                }
            }
            return false;
        }).toList();
        require(matches.size() == 1, "Expected one merged handler for " + bridgeMethod);
        require((matches.getFirst().access & Opcodes.ACC_STATIC) != 0, "The merged handler must remain static.");
        return matches.getFirst();
    }

    private static MethodInsnNode findCall(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                && name.equals(call.name) && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        require(matches.size() == 1, "Expected exactly one transformed call " + owner + "." + name + descriptor);
        require(matches.getFirst().getOpcode() == Opcodes.INVOKESTATIC, "The transformed call must remain static.");
        return matches.getFirst();
    }

    private static byte[] readClientResource(String resource) throws IOException {
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            ZipEntry entry = zip.getEntry(resource);
            if (entry == null) {
                return null;
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static final class BootstrapService implements IMixinServiceBootstrap {
        public String getName() { return "NVK Bridge offline transformer"; }
        public String getServiceClassName() { return OfflineService.class.getName(); }
        public void bootstrap() {}
    }

    /** Supplies class bytes while refusing client or LWJGL class loading. */
    public static final class OfflineService extends MixinServiceAbstract implements IClassProvider,
            IClassBytecodeProvider, ITransformerProvider, IClassTracker {
        private final ReEntranceLock lock = new ReEntranceLock(1);
        private IMixinTransformerFactory factory;
        private IConsumer<MixinEnvironment.Phase> phaseConsumer;
        private int targetClassLoads;

        public String getName() { return "NVK Bridge offline transformer"; }
        public boolean isValid() { return true; }
        public void prepare() {}
        public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.PREINIT; }
        public void offer(IMixinInternal internal) {
            if (internal instanceof IMixinTransformerFactory offered) {
                factory = offered;
            }
        }
        public void init() {}
        public void beginPhase() {}
        public void checkEnv(Object environment) {}
        public void wire(MixinEnvironment.Phase phase, IConsumer<MixinEnvironment.Phase> consumer) {
            phaseConsumer = consumer;
        }
        public void unwire() { phaseConsumer = null; }
        private void advancePhase(MixinEnvironment.Phase phase) {
            require(phaseConsumer != null, "Mixin did not provide its environment phase listener.");
            phaseConsumer.accept(phase);
        }
        public ReEntranceLock getReEntranceLock() { return lock; }
        public IClassProvider getClassProvider() { return this; }
        public IClassBytecodeProvider getBytecodeProvider() { return this; }
        public ITransformerProvider getTransformerProvider() { return this; }
        public IClassTracker getClassTracker() { return this; }
        public IMixinAuditTrail getAuditTrail() { return null; }
        public IFeatureValidator getFeatureValidator() { return IFeatureValidator.ALLOW_ALL; }
        public IAdviceProvider getAdviceProvider() { return IAdviceProvider.GENERIC; }
        public Collection<String> getPlatformAgents() { return List.of(); }
        public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("nvkbridge-offline"); }
        public Collection<IContainerHandle> getMixinContainers() { return List.of(); }
        public InputStream getResourceAsStream(String resource) {
            InputStream stream = MixinTransformationVerifier.class.getClassLoader().getResourceAsStream(resource);
            if (stream != null) {
                return stream;
            }
            try {
                byte[] bytes = readClientResource(resource);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read client resource " + resource, exception);
            }
        }
        public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
            return MixinEnvironment.CompatibilityLevel.JAVA_8;
        }
        public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
            return MixinEnvironment.CompatibilityLevel.JAVA_25;
        }
        public ILogger getLogger(String name) { return new LoggerAdapterConsole(name); }
        public URL[] getClassPath() { return new URL[0]; }
        public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
        public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
            if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.startsWith("org.lwjgl.")) {
                targetClassLoads++;
                throw new ClassNotFoundException("The offline transformer refuses client/native class loading: " + name);
            }
            return Class.forName(name, initialize, MixinTransformationVerifier.class.getClassLoader());
        }
        public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
            return findClass(name, initialize);
        }
        public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
            return getClassNode(name, false, 0);
        }
        public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
            return getClassNode(name, runTransformers, 0);
        }
        public ClassNode getClassNode(String name, boolean runTransformers, int flags)
                throws ClassNotFoundException, IOException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream stream = getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new ClassNotFoundException(name);
                }
                ClassNode node = new ClassNode();
                new ClassReader(stream).accept(node, flags);
                return node;
            }
        }
        public Collection<ITransformer> getTransformers() { return List.of(); }
        public Collection<ITransformer> getDelegatedTransformers() { return List.of(); }
        public void addTransformerExclusion(String name) {}
        public void registerInvalidClass(String name) {}
        public boolean isClassLoaded(String name) { return false; }
        public String getClassRestrictions(String name) { return ""; }
    }

    public static final class PropertiesService implements IGlobalPropertyService {
        private final Map<IPropertyKey, Object> properties = new ConcurrentHashMap<>();
        private record Key(String name) implements IPropertyKey {}
        public IPropertyKey resolveKey(String name) { return new Key(name); }
        @SuppressWarnings("unchecked")
        public <T> T getProperty(IPropertyKey key) { return (T) properties.get(key); }
        public void setProperty(IPropertyKey key, Object value) {
            if (value == null) {
                properties.remove(key);
            } else {
                properties.put(key, value);
            }
        }
        public <T> T getProperty(IPropertyKey key, T defaultValue) {
            T value = getProperty(key);
            return value == null ? defaultValue : value;
        }
        public String getPropertyString(IPropertyKey key, String defaultValue) {
            Object value = properties.get(key);
            return value == null ? defaultValue : value.toString();
        }
    }
}
