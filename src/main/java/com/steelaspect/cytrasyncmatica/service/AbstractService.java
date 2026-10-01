package com.steelaspect.cytrasyncmatica.service;

import com.steelaspect.cytrasyncmatica.Context;

abstract class AbstractService implements IService {

    Context context;

    @Override
    public Context getContext() {
        return context;
    }

    @Override
    public void setContext(final Context context) {
        this.context = context;
    }
}
