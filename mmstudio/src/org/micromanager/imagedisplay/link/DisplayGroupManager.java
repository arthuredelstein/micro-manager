package org.micromanager.imagedisplay.link;

import com.google.common.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.micromanager.api.display.DisplayManager;
import org.micromanager.api.display.DisplayWindow;
import org.micromanager.api.events.NewDisplayEvent;

import org.micromanager.imagedisplay.DisplayDestroyedEvent;

import org.micromanager.MMStudio;

import org.micromanager.utils.ReportingUtils;

/**
 * This class is responsible for tracking groupings of DisplayWindows and
 * handling synchronization of linked DisplaySettings across them.
 */
public class DisplayGroupManager {
   /**
    * This class listens to events from a specific DisplayWindow, and forwards
    * them to the DisplayGroupManager, so it can know which window originated
    * which event.
    */
   private class WindowListener {
      private DisplayWindow display_;
      private DisplayGroupManager master_;
      public WindowListener(DisplayWindow display, DisplayGroupManager master) {
         display_ = display;
         master_ = master;
         display_.registerForEvents(this);
      }

      @Subscribe
      public void onDisplaySettingsChanged(DisplaySettingsEvent event) {
         master_.onDisplaySettingsChanged(display_, event);
      }

      @Subscribe
      public void onNewLinkButton(LinkButtonCreatedEvent event) {
         master_.onNewLinkButton(display_, event);
      }

      @Subscribe
      public void onLinkButtonEvent(LinkButtonEvent event) {
         master_.onLinkButtonEvent(display_, event);
      }

      public DisplayWindow getDisplay() {
         return display_;
      }
   }

   private MMStudio studio_;
   private HashMap<DisplayWindow, HashSet<SettingsLinker>> displayToLinkers_;
   private HashMap<SettingsLinker, Boolean> linkerToIsLinked_;
   private ArrayList<WindowListener> listeners_;

   public DisplayGroupManager(MMStudio studio) {
      displayToLinkers_ = new HashMap<DisplayWindow, HashSet<SettingsLinker>>();
      linkerToIsLinked_ = new HashMap<SettingsLinker, Boolean>();
      listeners_ = new ArrayList<WindowListener>();
      studio_ = studio;
      // So we can be notified of newly-created displays.
      studio_.registerForEvents(this);
   }

   /**
    * A new display has arrived; register for its events.
    */
   @Subscribe
   public void onNewDisplayEvent(NewDisplayEvent event) {
      DisplayWindow display = event.getDisplayWindow();
      listeners_.add(new WindowListener(display, this));
      displayToLinkers_.put(display, new HashSet<SettingsLinker>());
   }

   @Subscribe
   public void onDisplayDestroyed(DisplayDestroyedEvent event) {
      DisplayWindow display = event.getDisplay();
      for (SettingsLinker linker : displayToLinkers_.get(display)) {
         linkerToIsLinked_.remove(linker);
      }
      displayToLinkers_.remove(display);
      for (WindowListener listener : listeners_) {
         if (listener.getDisplay() == display) {
            listeners_.remove(listener);
            break;
         }
      }
   }

   /**
    * A new LinkButton has been created; we need to start tracking its linker.
    */
   public void onNewLinkButton(DisplayWindow source,
         LinkButtonCreatedEvent event) {
      displayToLinkers_.get(source).add(event.getLinker());
      linkerToIsLinked_.put(event.getLinker(), false);
   }

   /**
    * A LinkButton has been clicked. Ensure other DisplayWindows get their
    * corresponding LinkButtons updated, and update our linker tracking.
    */
   public void onLinkButtonEvent(DisplayWindow source, LinkButtonEvent event) {
      try {
         // Find other displays for this datastore and update their
         // corresponding link buttons.
         SettingsLinker linker = event.getLinker();
         boolean isLinked = event.getIsLinked();
         RemoteLinkEvent notifyEvent = new RemoteLinkEvent(linker, isLinked);
         for (DisplayWindow display : displayToLinkers_.keySet()) {
            if (display == source ||
                  display.getDatastore() != source.getDatastore()) {
               // No need to notify this one.
               continue;
            }
            display.postEvent(notifyEvent);
         }
         // Not just this linker, but all others with the same ID need to be
         // updated.
         linkerToIsLinked_.put(linker, isLinked);
         for (SettingsLinker alt : linkerToIsLinked_.keySet()) {
            if (linker.getID() == alt.getID()) {
               linkerToIsLinked_.put(alt, isLinked);
            }
         }
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Couldn't redistribute LinkButtonEvent");
      }
   }

   /**
    * One DisplayWindow has changed settings; check if others care.
    */
   public void onDisplaySettingsChanged(DisplayWindow source,
         DisplaySettingsEvent event) {
      for (DisplayWindow display : displayToLinkers_.keySet()) {
         if (display == source) {
            continue;
         }
         for (SettingsLinker linker : displayToLinkers_.get(display)) {
            if (!linkerToIsLinked_.get(linker)) {
               // This linker isn't active.
               continue;
            }
            List<Class<?>> classes = linker.getRelevantEventClasses();
            for (Class<?> eventClass : classes) {
               if (eventClass.isInstance(event) &&
                     linker.getShouldApplyChanges(event)) {
                  linker.applyChange(event);
                  break;
               }
            }
         }
      }
   }
}
