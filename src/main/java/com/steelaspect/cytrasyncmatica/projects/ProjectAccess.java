package com.steelaspect.cytrasyncmatica.projects;

/** Who may create, delete and edit projects. Viewing needs nothing. */
public final class ProjectAccess {
    public static final String MANAGE_PERMISSION = "cytra-syncmatica.project.manage";
    public static final int MANAGE_PERMISSION_LEVEL = 2;

    private ProjectAccess() {
    }
}
