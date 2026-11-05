package com.example.charlod;

import com.example.charlod.net.FarNet;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(CharLod.MODID)
public final class CharLod {
    public static final String MODID = "charlod";
    public static final Logger LOG = LogUtils.getLogger();

    public CharLod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        FarNet.register();
        FarNet.registerServerSide();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientBoot::start);
    }

    private static final class ClientBoot {
        static void start() {
            com.example.charlod.client.FarClient.init(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }
}
