package dev.nvkbridge.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Checks the released client as data; it never loads or runs Minecraft classes. */
public final class MinecraftHookVerifier {
    private static final String CLIENT_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87";
    private static final String BOOTSTRAP = "com/mojang/blaze3d/platform/NativeLibrariesBootstrap";
    private static final String BACKEND = "com/mojang/renderpearl/backend/vulkan/VulkanBackend";
    private static final String MIXIN = "dev/nvkbridge/mixin/NativeLibrariesBootstrapMixin";
    private static final String BRIDGE = "dev/nvkbridge/runtime/NvkBridge";
    private static final String CALLBACK =
        "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String VK_CREATE_TARGET = "Lorg/lwjgl/vulkan/VK;create()V";

    private MinecraftHookVerifier() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass the path to the official Minecraft 26.3 client JAR.");
        Path client = Path.of(args[0]).toRealPath();
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream stream = Files.newInputStream(client)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        String actualSha1 = HexFormat.of().formatHex(digest.digest());
        require(CLIENT_SHA1.equals(actualSha1),
            "The client JAR is not the pinned official Minecraft 26.3 artifact: " + actualSha1);
        System.out.println("PASS: official Minecraft 26.3 client SHA-1 matches " + CLIENT_SHA1);

        try (ZipFile jar = new ZipFile(client.toFile())) {
            verifyBootstrap(readClass(jar, BOOTSTRAP));
            verifyMixin(readOwnClass(MIXIN));
            verifySdlAlignment(readClass(jar, BACKEND));
            verifyWarningUi(jar);
            int classes = verifyNoGlfwClassReferences(jar);
            System.out.println("PASS: " + classes + " client class files contain no GLFW class references.");
        }
        System.out.println(
            "Structural hook compatibility verified. Mixin application, loader startup and macOS GPU behavior were not executed."
        );
    }

    private static void verifyBootstrap(ClassNode bootstrap) {
        MethodNode method = findMethod(bootstrap, "tryLoadingVulkan", "()Z");
        require((method.access & Opcodes.ACC_STATIC) != 0, "The Vulkan bootstrap probe must remain static.");
        List<MethodInsnNode> calls = findCalls(method, "org/lwjgl/vulkan/VK", "create", "()V");
        require(calls.size() == 1, "Expected exactly one VK.create()V call in the bootstrap probe.");
        MethodInsnNode call = calls.getFirst();
        require(call.getOpcode() == Opcodes.INVOKESTATIC, "VK.create()V must remain an INVOKESTATIC call.");
        int callIndex = method.instructions.indexOf(call);
        boolean caught = false;
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if ("java/lang/Throwable".equals(block.type)
                && method.instructions.indexOf(block.start) < callIndex
                && callIndex < method.instructions.indexOf(block.end)) {
                caught = true;
            }
        }
        require(caught, "The VK.create()V hook must remain within vanilla's Throwable handler.");
        System.out.println("PASS: the exact static bootstrap probe has one VK.create()V call inside its Throwable handler.");
    }

    private static void verifyWarningUi(ZipFile jar) throws IOException {
        ClassNode client = readClass(jar, "net/minecraft/client/Minecraft");
        findMethod(client, "onGameLoadFinished", "(Lnet/minecraft/client/GameLoadCookie;)V");
        require(client.fields.stream().anyMatch(field -> "gui".equals(field.name)
            && "Lnet/minecraft/client/gui/Gui;".equals(field.desc)
            && (field.access & Opcodes.ACC_PUBLIC) != 0), "The startup toast needs Minecraft's public GUI field.");
        require((findMethod(readClass(jar, "com/mojang/blaze3d/systems/RenderSystem"), "getDevice",
            "()Lcom/mojang/renderpearl/api/device/GpuDevice;").access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
            == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC), "RenderSystem.getDevice must remain public/static.");
        findMethod(readClass(jar, "com/mojang/renderpearl/api/device/GpuDevice"), "getDeviceInfo",
            "()Lcom/mojang/renderpearl/api/device/DeviceInfo;");
        findMethod(readClass(jar, "com/mojang/renderpearl/api/device/DeviceInfo"), "backendName", "()Ljava/lang/String;");
        findMethod(readClass(jar, "net/minecraft/client/gui/Gui"), "toastManager",
            "()Lnet/minecraft/client/gui/components/toasts/ToastManager;");
        findMethod(readClass(jar, "net/minecraft/network/chat/Component"), "literal",
            "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;");
        findMethod(readClass(jar, "net/minecraft/client/gui/components/toasts/SystemToast$SystemToastId"), "<init>", "(J)V");
        findMethod(readClass(jar, "net/minecraft/client/gui/components/toasts/SystemToast"), "add",
            "(Lnet/minecraft/client/gui/components/toasts/ToastManager;"
                + "Lnet/minecraft/client/gui/components/toasts/SystemToast$SystemToastId;"
                + "Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V");
        System.out.println("PASS: startup warning hook, actual backend query and toast API match the pinned client.");
    }

    private static void verifyMixin(ClassNode mixin) {
        AnnotationNode target = findAnnotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, MIXIN_ANNOTATION);
        require(target != null, "The compiled bootstrap Mixin is missing @Mixin.");
        require(Boolean.FALSE.equals(value(target, "remap")), "The bootstrap Mixin must use remap=false.");
        require(List.of(BOOTSTRAP.replace('/', '.')).equals(value(target, "targets")),
            "The bootstrap Mixin targets a different client class.");
        int injectors = 0;
        for (MethodNode method : mixin.methods) {
            if (findAnnotation(method.visibleAnnotations, method.invisibleAnnotations, INJECT_ANNOTATION) != null) {
                injectors++;
            }
        }
        require(injectors == 2, "Expected exactly the configuration and verification bootstrap injectors.");
        verifyInjector(mixin, "nvkbridge$configureLibrary", "BEFORE", "configureBeforeVulkanCreate");
        verifyInjector(mixin, "nvkbridge$verifyLibrary", "AFTER", "verifyLoadedProvider");
        System.out.println("PASS: both static Mixin handlers use the exact target, BEFORE/AFTER positions and require=1.");
    }

    private static void verifyInjector(ClassNode mixin, String name, String shift, String bridgeMethod) {
        MethodNode handler = findMethod(mixin, name, CALLBACK);
        require((handler.access & Opcodes.ACC_STATIC) != 0, "Mixin callback " + name + " must remain static.");
        AnnotationNode inject = findAnnotation(handler.visibleAnnotations, handler.invisibleAnnotations, INJECT_ANNOTATION);
        require(inject != null, "Missing @Inject on " + name);
        require(List.of("tryLoadingVulkan()Z").equals(value(inject, "method")),
            "Unexpected target method on " + name);
        require(Integer.valueOf(1).equals(value(inject, "require")), "Injector " + name + " must require one match.");
        Object positions = value(inject, "at");
        require(positions instanceof List<?> && ((List<?>) positions).size() == 1,
            "Injector " + name + " must contain exactly one @At position.");
        Object position = ((List<?>) positions).getFirst();
        require(position instanceof AnnotationNode, "Invalid @At annotation on " + name);
        AnnotationNode at = (AnnotationNode) position;
        require("Lorg/spongepowered/asm/mixin/injection/At;".equals(at.desc), "Unexpected @At type on " + name);
        require("INVOKE".equals(value(at, "value")), "Injector " + name + " must match an invocation.");
        require(VK_CREATE_TARGET.equals(value(at, "target")), "Injector " + name + " targets a different invocation.");
        Object actualShift = value(at, "shift");
        require(actualShift instanceof String[]
                && ((String[]) actualShift).length == 2
                && "Lorg/spongepowered/asm/mixin/injection/At$Shift;".equals(((String[]) actualShift)[0])
                && shift.equals(((String[]) actualShift)[1]),
            "Injector " + name + " has the wrong invocation position.");
        List<MethodInsnNode> bridgeCalls = findCalls(handler, BRIDGE, bridgeMethod, "()V");
        require(bridgeCalls.size() == 1 && bridgeCalls.getFirst().getOpcode() == Opcodes.INVOKESTATIC,
            "Injector " + name + " must call its shared runtime handler exactly once.");
    }

    private static void verifySdlAlignment(ClassNode backend) {
        MethodNode method = findMethod(backend, "loadLibrary", "()V");
        List<MethodInsnNode> pathCalls = findCalls(method, "org/lwjgl/system/SharedLibrary", "getPath", "()Ljava/lang/String;");
        require(pathCalls.size() == 1, "Expected one loaded SharedLibrary path lookup in VulkanBackend.loadLibrary().");
        MethodInsnNode pathCall = pathCalls.getFirst();
        AbstractInsnNode cast = previousInstruction(pathCall);
        require(cast instanceof TypeInsnNode
                && cast.getOpcode() == Opcodes.CHECKCAST
                && "org/lwjgl/system/SharedLibrary".equals(((TypeInsnNode) cast).desc),
            "SDL's path must originate from the loaded SharedLibrary.");
        require(matches(previousInstruction(cast), "org/lwjgl/vulkan/VK", "getFunctionProvider",
                "()Lorg/lwjgl/system/FunctionProvider;"),
            "SDL's path must originate from LWJGL's current Vulkan provider.");
        require(matches(nextInstruction(pathCall), "org/lwjgl/sdl/SDLVulkan", "SDL_Vulkan_LoadLibrary",
                "(Ljava/lang/CharSequence;)Z"),
            "Vanilla must pass LWJGL's loaded shared-library path directly to SDL.");

        List<MethodInsnNode> sdlGetters = findCalls(method, "org/lwjgl/sdl/SDLVulkan",
            "SDL_Vulkan_GetVkGetInstanceProcAddr", "()J");
        require(sdlGetters.size() == 1, "Expected exactly one SDL Vulkan proc-address check.");
        MethodInsnNode sdlGetter = sdlGetters.getFirst();
        AbstractInsnNode providerGetter = previousInstruction(sdlGetter);
        require(matches(providerGetter, "org/lwjgl/system/FunctionProvider", "getFunctionAddress",
                "(Ljava/lang/CharSequence;)J"),
            "The SDL proc-address comparison must use LWJGL's provider.");
        AbstractInsnNode procName = previousInstruction(providerGetter);
        require(procName instanceof LdcInsnNode && "vkGetInstanceProcAddr".equals(((LdcInsnNode) procName).cst),
            "The SDL proc-address comparison must query vkGetInstanceProcAddr.");
        require(matches(previousInstruction(procName), "org/lwjgl/vulkan/VK", "getFunctionProvider",
                "()Lorg/lwjgl/system/FunctionProvider;"),
            "The proc-address lookup must use the same Vulkan function provider.");
        AbstractInsnNode comparison = nextInstruction(sdlGetter);
        require(comparison != null && comparison.getOpcode() == Opcodes.LCMP,
            "The two Vulkan proc addresses must remain compared as native pointers.");
        AbstractInsnNode branch = nextInstruction(comparison);
        require(branch instanceof JumpInsnNode && branch.getOpcode() == Opcodes.IFEQ,
            "Vanilla must reject a mismatched SDL Vulkan proc address.");
        int branchIndex = method.instructions.indexOf(branch);
        int successIndex = method.instructions.indexOf(((JumpInsnNode) branch).label);
        boolean mismatchUnloads = findCalls(method, "org/lwjgl/sdl/SDLVulkan", "SDL_Vulkan_UnloadLibrary", "()V")
            .stream().anyMatch(call -> method.instructions.indexOf(call) > branchIndex
                && method.instructions.indexOf(call) < successIndex);
        require(mismatchUnloads, "The mismatched-proc-address path must unload SDL's Vulkan library.");
        System.out.println("PASS: vanilla passes LWJGL's library path to SDL and rejects a mismatched vkGetInstanceProcAddr.");
    }

    private static int verifyNoGlfwClassReferences(ZipFile jar) throws IOException {
        int classes = 0;
        for (ZipEntry entry : jar.stream().filter(candidate -> candidate.getName().endsWith(".class")).toList()) {
            try (InputStream stream = jar.getInputStream(entry)) {
                ClassReader reader = new ClassReader(stream);
                char[] buffer = new char[reader.getMaxStringLength()];
                for (int item = 1; item < reader.getItemCount(); item++) {
                    int offset = reader.getItem(item);
                    if (offset != 0 && reader.readByte(offset - 1) == 7) {
                        String name = reader.readUTF8(offset, buffer);
                        require(name == null || !name.startsWith("org/lwjgl/glfw/"),
                            "Unexpected GLFW class dependency " + name + " in " + entry.getName());
                    }
                }
            }
            classes++;
        }
        return classes;
    }

    private static ClassNode readClass(ZipFile jar, String name) throws IOException {
        ZipEntry entry = jar.getEntry(name + ".class");
        require(entry != null, "Missing client class " + name);
        try (InputStream stream = jar.getInputStream(entry)) {
            return readClass(stream);
        }
    }

    private static ClassNode readOwnClass(String name) throws IOException {
        try (InputStream stream = MinecraftHookVerifier.class.getClassLoader().getResourceAsStream(name + ".class")) {
            require(stream != null, "Missing compiled Mixin resource " + name);
            return readClass(stream);
        }
    }

    private static ClassNode readClass(InputStream stream) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        List<MethodNode> matches = node.methods.stream()
            .filter(method -> name.equals(method.name) && descriptor.equals(method.desc)).toList();
        require(matches.size() == 1, "Expected one method " + node.name + "." + name + descriptor);
        return matches.getFirst();
    }

    private static List<MethodInsnNode> findCalls(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (matches(instruction, owner, name, descriptor)) {
                calls.add((MethodInsnNode) instruction);
            }
        }
        return calls;
    }

    private static boolean matches(AbstractInsnNode node, String owner, String name, String descriptor) {
        return node instanceof MethodInsnNode call
            && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc);
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode node) {
        AbstractInsnNode next = node.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode node) {
        AbstractInsnNode previous = node.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (visible != null) {
            annotations.addAll(visible);
        }
        if (invisible != null) {
            annotations.addAll(invisible);
        }
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int index = 0; index < annotation.values.size(); index += 2) {
                if (key.equals(annotation.values.get(index))) {
                    return annotation.values.get(index + 1);
                }
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
