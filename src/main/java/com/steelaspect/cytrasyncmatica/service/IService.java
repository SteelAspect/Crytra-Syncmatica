package com.steelaspect.cytrasyncmatica.service;

import com.steelaspect.cytrasyncmatica.Context;
import com.steelaspect.cytrasyncmatica.service.IServiceConfiguration;

public interface IService {

    Context getContext();

    void setContext(Context context);

    void getDefaultConfiguration(IServiceConfiguration configuration);

    String getConfigKey();

    void configure(IServiceConfiguration configuration);

    void startup();

    void shutdown();
}
