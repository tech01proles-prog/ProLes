package com.proles.server.routes

import io.ktor.server.routing.Route

fun Route.dataRoutes() {
    fcmRoutes()
    timeEntryRoutes()
    projectCostRoutes()
    projectRoutes()
    expenseRoutes()
    incomeRoutes()
    positionRoutes()
    userRoutes()
    absenceRoutes()
    businessTripRoutes()
    notificationRoutes()
    personalTimesheetRoutes()
    notificationPreferenceRoutes()
    permissionRoutes()
    ticketRoutes()
    payrollRoutes()
}